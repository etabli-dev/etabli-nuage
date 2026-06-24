// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Background-mode checker. URLSession only — no third-party deps. The
// foreground checker lives inside RunView since it owns the WKWebView.
//
// User-agent is set on the request so server logs are honest about who's
// hitting them; we follow redirects up to the URLSession default cap.

public let userAgent = "EtabliProbe/1.0 (+QA link-checker; sequential)"

public actor BackgroundChecker {
    public let timeout: TimeInterval
    public let installId: String
    private let session: URLSession

    public init(timeout: TimeInterval, installId: String) {
        self.timeout = timeout
        self.installId = installId
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpAdditionalHeaders = ["User-Agent": userAgent]
        cfg.timeoutIntervalForRequest = timeout
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: cfg)
    }

    public func probe(_ entry: UrlEntry) async -> CheckResult {
        let now = Date()
        guard let normalized = entry.normalized, let url = URL(string: normalized) else {
            return CheckResult(rowIndex: entry.rowIndex, url: entry.raw,
                               mode: "background", result: "SKIPPED: invalid URL",
                               finalURL: nil, durationMs: 0,
                               timestampLocal: now, timestampUtc: now,
                               installId: installId)
        }
        let started = Date()
        do {
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: req)
            _ = data        // body intentionally dropped
            let elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
            let http = response as? HTTPURLResponse
            let code = http?.statusCode ?? 0
            return CheckResult(rowIndex: entry.rowIndex, url: normalized,
                               mode: "background", result: String(code),
                               finalURL: response.url?.absoluteString,
                               durationMs: elapsedMs,
                               timestampLocal: now, timestampUtc: now.toUTC(),
                               installId: installId)
        } catch let urlErr as URLError where urlErr.code == .timedOut {
            let elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
            return CheckResult(rowIndex: entry.rowIndex, url: normalized,
                               mode: "background",
                               result: "FAILED: timeout (\(Int(timeout))s)",
                               finalURL: nil, durationMs: elapsedMs,
                               timestampLocal: now, timestampUtc: now.toUTC(),
                               installId: installId)
        } catch {
            let elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
            return CheckResult(rowIndex: entry.rowIndex, url: normalized,
                               mode: "background",
                               result: "FAILED: \(error.localizedDescription)",
                               finalURL: nil, durationMs: elapsedMs,
                               timestampLocal: now, timestampUtc: now.toUTC(),
                               installId: installId)
        }
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
