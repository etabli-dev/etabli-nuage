import Foundation
@main struct CheckCore {
    static func main() async throws {
        let csv = try CsvLoader.parse(Data("example.org;Company\rsecond.org;Second\r".utf8))
        precondition(!csv.hasHeader && csv.rows.count == 2 && csv.rows[0][0] == "example.org")
        precondition(csv.withHeader(true).withHeader(false).rows == csv.rows)
        let quoted = try CsvLoader.parse(Data("\u{FEFF}url;name\r\nhttps://example.org;\"one,two,three\nfour\"\r\n".utf8))
        precondition(quoted.hasHeader && quoted.rows.count == 1 && quoted.headers.count == 2)
        for bad in ["url\n\"unclosed", "url\n\"closed\"extra", "url\nunexpected\"quote"] {
            do { _ = try CsvLoader.parse(Data(bad.utf8)); fatalError("Malformed CSV accepted") } catch is CsvError { }
        }
        do { _ = try CsvLoader.parse(Data(repeating: 32, count: maxCSVBytes + 1)); fatalError("Oversize accepted") } catch is CsvError { }
        precondition(UrlNormalize.normalize("HTTPS://example.org/a%20b") == "https://example.org/a%20b")
        precondition(UrlNormalize.normalize("https://user:secret@example.org") == nil)
        precondition(UrlNormalize.normalize("file:///etc/passwd") == nil)
        let profile = try UsageProfileParser.parse("2026-10-28 08:00:59 com.example OPEN\n2026-10-28 08:01:01 com.example CLOSE\n2026-10-29 09:00:00 com.example CLOSE")
        precondition(profile.totalDays == 2 && profile.perMinute[480] == 0.5 && profile.perMinute[481] == 0.5)
        let invalidDate = try UsageProfileParser.parse("2026-02-30 08:00:00 com.example OPEN")
        precondition(invalidDate.totalRecords == 0 && invalidDate.skippedLines == 1)
        do {
            _ = try UsageProfileParser.parse("2020-01-01 08:00:00 com.example OPEN\n2026-01-01 08:00:00 com.example CLOSE")
            fatalError("Unbounded interval accepted")
        } catch is CsvError { }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let result = CheckResult(rowIndex: 1, url: "https://example.org", mode: "background", result: "200", finalURL: nil, durationMs: 1, timestampLocal: Date(), timestampUtc: Date(), installId: "test")
        let first = CheckLogger(rotation: .perRun, directory: root)
        let second = CheckLogger(rotation: .perRun, directory: root)
        try first.append(result); try second.append(result)
        precondition(first.currentURL != second.currentURL)
        let logged = try CsvLoader.parse(CsvLoader.read(first.currentURL!))
        precondition(logged.rows.count == 1)
        first.close(); second.close()
        let bad = CheckLogger(rotation: .perRun, directory: first.currentURL!)
        do { try bad.append(result); fatalError("Write failure hidden") } catch { precondition(bad.memory.isEmpty) }
        let dav = WebDavClient(WebDavCreds(baseURL: "https://example.org/nextcloud/", username: "a+b", appPassword: "secret"))
        let url = await dav.userRoot("100% #1?.csv")
        precondition(url!.absoluteString == "https://example.org/nextcloud/remote.php/dav/files/a%2Bb/100%25%20%231%3F.csv")
        let invalidPath = await dav.userRoot("../escape")
        precondition(invalidPath == nil)
        if CommandLine.arguments.count > 1 {
            let base = CommandLine.arguments[1]
            let checker = BackgroundChecker(timeout: 2, installId: "test")
            let fast = try await checker.probe(UrlEntry(rowIndex: 1, raw: base + "/ok", normalized: base + "/ok"))
            FileHandle.standardError.write(Data("Immediate probe: \(fast.result)\n".utf8))
            let start = Date()
            let response = try await checker.probe(UrlEntry(rowIndex: 1, raw: base + "/slow-body", normalized: base + "/slow-body"))
            FileHandle.standardError.write(Data("HTTP probe: \(response.result), elapsed \(Date().timeIntervalSince(start)) s\n".utf8))
            precondition(response.result == "200" && Date().timeIntervalSince(start) < 1.5)
            // On the tested macOS runtime CFNetwork waits for some body data before exposing headers.
            // Such a stalled response must still respect the whole-probe timeout.
            let short = BackgroundChecker(timeout: 0.15, installId: "test")
            let stalled = try await short.probe(UrlEntry(rowIndex: 1, raw: base + "/sniff-delayed", normalized: base + "/sniff-delayed"))
            precondition(stalled.result.hasPrefix("FAILED:"))
            let redirected = try await checker.probe(UrlEntry(rowIndex: 1, raw: base + "/redirect", normalized: base + "/redirect"))
            precondition(redirected.result == "404" && redirected.finalURL == base + "/missing")
            do { _ = try await boundedRequest(URLRequest(url: URL(string: base + "/large")!), limit: 100); fatalError("Unbounded response") } catch { }
            let task = Task { try await checker.probe(UrlEntry(rowIndex: 1, raw: base + "/slow-headers", normalized: base + "/slow-headers")) }
            task.cancel()
            do { _ = try await task.value; fatalError("Cancellation swallowed") } catch is CancellationError { }
        }
        print("CSV, URL, durable logging, DAV paths and HTTP core checks passed")
    }
}
