// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Append-only CSV logger. Writes one row per check to the app's Documents
// folder under probe_logs/. Each row is committed (sync flush) before we
// move on to the next URL, so a kill mid-run doesn't lose progress.

public final class CheckLogger {
    public let rotation: LogRotation
    public private(set) var memory: [CheckResult] = []
    public private(set) var currentURL: URL?

    private let runStamp: String
    private var handle: FileHandle?
    private let dirURL: URL

    public init(rotation: LogRotation, directory: URL? = nil) {
        self.rotation = rotation
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd-HHmmss"
        self.runStamp = f.string(from: Date()) + "-" + UUID().uuidString
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.dirURL = directory ?? docs.appendingPathComponent("probe_logs", isDirectory: true)
    }

    public func append(_ r: CheckResult) throws {
        try ensureOpen()
        let line = r.csvRow().map(escape).joined(separator: ",") + "\n"
        guard let handle else { throw CocoaError(.fileWriteUnknown) }
        try handle.write(contentsOf: Data(line.utf8))
        try handle.synchronize()
        memory.append(r)
    }

    public func close() {
        try? handle?.close()
        handle = nil
    }

    private func ensureOpen() throws {
        if handle != nil { return }
        try FileManager.default.createDirectory(at: dirURL, withIntermediateDirectories: true)
        let name = rotation == .perRun ? "probe_\(runStamp).csv" : "probe.csv"
        let url = dirURL.appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: url.path) {
            let header = CheckResult.csvHeader.map(escape).joined(separator: ",") + "\n"
            try Data(header.utf8).write(to: url, options: .withoutOverwriting)
        }
        let opened = try FileHandle(forWritingTo: url)
        do { try opened.seekToEnd() } catch { try? opened.close(); throw error }
        handle = opened; currentURL = url
    }

    // RFC-4180 escape — wrap fields containing comma, quote, or newline.
    private func escape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") {
            let q = s.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(q)\""
        }
        return s
    }
}
