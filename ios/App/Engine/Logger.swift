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

    public init(rotation: LogRotation) {
        self.rotation = rotation
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd-HHmmss"
        self.runStamp = f.string(from: Date())
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.dirURL = docs.appendingPathComponent("probe_logs", isDirectory: true)
    }

    public func append(_ r: CheckResult) {
        ensureOpen()
        memory.append(r)
        let line = (r.csvRow().map(escape) + ["\n"]).joined(separator: ",")
            .replacingOccurrences(of: ",\n", with: "\n")
        if let data = line.data(using: .utf8) {
            try? handle?.write(contentsOf: data)
        }
    }

    public func close() {
        try? handle?.close()
        handle = nil
    }

    private func ensureOpen() {
        if handle != nil { return }
        try? FileManager.default.createDirectory(at: dirURL,
                                                 withIntermediateDirectories: true)
        let name = rotation == .perRun ? "probe_\(runStamp).csv" : "probe.csv"
        let url = dirURL.appendingPathComponent(name)
        currentURL = url
        let isNew = !FileManager.default.fileExists(atPath: url.path)
        if isNew {
            let header = CheckResult.csvHeader.map(escape).joined(separator: ",") + "\n"
            FileManager.default.createFile(atPath: url.path, contents: header.data(using: .utf8))
        }
        handle = try? FileHandle(forWritingTo: url)
        if let h = handle { try? h.seekToEnd() }
    }

    // RFC-4180 escape — wrap fields containing comma, quote, or newline.
    private func escape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") {
            let q = s.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(q)\""
        }
        return s
    }
}
