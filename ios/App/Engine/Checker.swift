// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

public let userAgent = "EtabliNuage/1.0 (+QA link-checker; sequential)"

// Each transfer owns an ephemeral session, a byte limit and a cancellable continuation.
private final class BoundedTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let limit: Int
    private let headersOnly: Bool
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?
    private var completed: Result<(Data, HTTPURLResponse), Error>?
    private var response: HTTPURLResponse?
    private var body = Data()
    private var session: URLSession?
    init(limit: Int, headersOnly: Bool) { self.limit = limit; self.headersOnly = headersOnly }
    func run(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if let completed { lock.unlock(); continuation.resume(with: completed); return }
                self.continuation = continuation
                let config = URLSessionConfiguration.ephemeral
                config.timeoutIntervalForRequest = request.timeoutInterval
                config.timeoutIntervalForResource = request.timeoutInterval
                config.requestCachePolicy = .reloadIgnoringLocalCacheData
                let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
                self.session = session
                let task = session.dataTask(with: request)
                lock.unlock()
                task.resume()
            }
        } onCancel: { self.finish(.failure(CancellationError())) }
    }
    private func finish(_ result: Result<(Data, HTTPURLResponse), Error>) {
        lock.lock()
        guard completed == nil else { lock.unlock(); return }
        completed = result
        let pending = continuation; continuation = nil
        let currentSession = session; session = nil
        lock.unlock()
        currentSession?.invalidateAndCancel()
        pending?.resume(with: result)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel); finish(.failure(URLError(.badServerResponse))); return
        }
        if headersOnly { completionHandler(.cancel); finish(.success((Data(), http))); return }
        if response.expectedContentLength > limit {
            completionHandler(.cancel); finish(.failure(CsvError.invalid("Response exceeds allowed size"))); return
        }
        lock.lock(); self.response = http; lock.unlock()
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard completed == nil else { lock.unlock(); return }
        if data.count > limit - body.count {
            lock.unlock(); finish(.failure(CsvError.invalid("Response exceeds allowed size"))); return
        }
        body.append(data); lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)); return }
        lock.lock(); let response = response; let body = body; lock.unlock()
        if let response { finish(.success((body, response))) }
        else { finish(.failure(URLError(.badServerResponse))) }
    }
}

func boundedRequest(_ request: URLRequest, limit: Int, headersOnly: Bool = false) async throws -> (Data, HTTPURLResponse) {
    try Task.checkCancellation()
    return try await BoundedTransfer(limit: limit, headersOnly: headersOnly).run(request)
}

public actor BackgroundChecker {
    public let timeout: TimeInterval
    public let installId: String
    public init(timeout: TimeInterval, installId: String) { self.timeout = timeout; self.installId = installId }
    public func probe(_ entry: UrlEntry) async throws -> CheckResult {
        try Task.checkCancellation()
        let started = Date()
        func result(_ outcome: String, _ finalURL: String? = nil) -> CheckResult {
            CheckResult(rowIndex: entry.rowIndex, url: entry.normalized ?? entry.raw, mode: "background",
                        result: outcome, finalURL: finalURL, durationMs: Int(Date().timeIntervalSince(started) * 1000),
                        timestampLocal: started, timestampUtc: started, installId: installId)
        }
        guard timeout > 0 && timeout <= 120 else { return result("FAILED: invalid timeout") }
        guard let normalized = entry.normalized, let valid = UrlNormalize.normalize(normalized), var url = URL(string: valid)
        else { return result("SKIPPED: invalid URL") }
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        do {
            for hop in 0...10 {
                try Task.checkCancellation()
                let remaining = deadline - ProcessInfo.processInfo.systemUptime
                guard remaining > 0 else { throw URLError(.timedOut) }
                var request = URLRequest(url: url, timeoutInterval: remaining)
                request.httpMethod = "GET"
                request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
                let (_, response) = try await boundedRequest(request, limit: 0, headersOnly: true)
                try Task.checkCancellation()
                if [301, 302, 303, 307, 308].contains(response.statusCode), let location = response.value(forHTTPHeaderField: "Location") {
                    guard hop < 10, let resolved = URL(string: location, relativeTo: url)?.absoluteURL,
                          let safe = UrlNormalize.normalize(resolved.absoluteString), let next = URL(string: safe)
                    else { return result("FAILED: unsafe redirect or redirect limit", url.absoluteString) }
                    url = next
                } else { return result(String(response.statusCode), url.absoluteString) }
            }
        } catch {
            try Task.checkCancellation()
            return result("FAILED: \(error.localizedDescription)")
        }
        return result("FAILED: redirect limit")
    }
}

public enum ForegroundResult {
    public static func build(entry: UrlEntry, installId: String,
                             durationMs: Int, finalURL: String?,
                             outcome: String) -> CheckResult {
        let now = Date()
        return CheckResult(
            rowIndex: entry.rowIndex,
            url: entry.normalized ?? entry.raw,
            mode: "foreground", result: outcome,
            finalURL: finalURL, durationMs: durationMs,
            timestampLocal: now, timestampUtc: now.toUTC(),
            installId: installId
        )
    }
}

private extension Date {
    // Round-trip through TimeZone(.gmt) to get a date whose stringification
    // is the same instant, only labelled UTC.
    func toUTC() -> Date { self }    // identical instant; the formatter does the display
}
