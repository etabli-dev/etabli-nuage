// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
import Foundation

public let maxCSVBytes = 10 * 1024 * 1024
public enum CsvError: LocalizedError {
    case invalid(String)
    public var errorDescription: String? { if case .invalid(let message) = self { return message }; return nil }
}
public struct CsvPreview: Sendable {
    public let headers: [String]
    public let rows: [[String]]
    public let hasHeader: Bool
    private let source: [[String]]
    init(source: [[String]], hasHeader: Bool) {
        self.source = source; self.hasHeader = hasHeader && !source.isEmpty
        let count = source.first?.count ?? 0
        headers = self.hasHeader ? source[0].map { $0.isEmpty ? "(blank)" : $0 } : (0..<count).map { "col \($0 + 1)" }
        rows = self.hasHeader ? Array(source.dropFirst()) : source
    }
    public func withHeader(_ value: Bool) -> CsvPreview { CsvPreview(source: source, hasHeader: value) }
    public func column(_ idx: Int) -> [String] { guard headers.indices.contains(idx) else { return [] }; return rows.map { $0[idx] } }
}
public enum CsvLoader {
    public static func read(_ url: URL) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: maxCSVBytes + 1) ?? Data()
        guard data.count <= maxCSVBytes else { throw CsvError.invalid("CSV exceeds 10 MiB") }
        return data
    }
    public static func parse(_ data: Data, hasHeader: Bool? = nil) throws -> CsvPreview {
        guard data.count <= maxCSVBytes else { throw CsvError.invalid("CSV exceeds 10 MiB") }
        var text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        let chars = Array(text.unicodeScalars)
        var scores: [Unicode.Scalar: Int] = [",": 0, ";": 0, "\t": 0]
        var quoted = false; var records = 0; var i = 0
        while i < chars.count && records < 10 {
            let c = chars[i]
            if c == "\"" {
                if quoted && i + 1 < chars.count && chars[i + 1] == "\"" { i += 2; continue }
                quoted.toggle()
            } else if !quoted {
                if scores[c] != nil { scores[c, default: 0] += 1 }
                if c == "\n" || c == "\r" { records += 1 }
            }
            i += 1
        }
        let delimiter = [Unicode.Scalar(","), ";", "\t"].max { scores[$0, default: 0] < scores[$1, default: 0] }!
        var rows: [[String]] = []; var row: [String] = []; var field = ""
        var closed = false; var cells = 0; quoted = false; i = 0
        func endField() throws {
            guard row.count < 1024 && cells < 1_000_000 else { throw CsvError.invalid("CSV has too many columns or cells") }
            row.append(field); field = ""; closed = false; cells += 1
        }
        func endRow() throws {
            try endField()
            guard rows.count < 100_000 else { throw CsvError.invalid("CSV has too many records") }
            rows.append(row); row = []
        }
        while i < chars.count {
            let c = chars[i]
            if quoted {
                if c == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" { field.append("\""); i += 2; continue }
                    quoted = false; closed = true
                } else { field.unicodeScalars.append(c) }
            } else if c == delimiter { try endField() }
            else if c == "\r" || c == "\n" {
                try endRow()
                if c == "\r" && i + 1 < chars.count && chars[i + 1] == "\n" { i += 1 }
            } else if c == "\"" && field.isEmpty && !closed { quoted = true }
            else {
                guard c != "\"" && !closed else { throw CsvError.invalid("Unexpected quote or text after a quoted field") }
                field.unicodeScalars.append(c)
            }
            i += 1
        }
        guard !quoted else { throw CsvError.invalid("Unclosed quoted field") }
        if !field.isEmpty || !row.isEmpty || closed { try endRow() }
        let width = rows.map(\.count).max() ?? 0
        guard width * rows.count <= 1_000_000 else { throw CsvError.invalid("CSV is too wide to preview") }
        let padded = rows.map { $0 + Array(repeating: "", count: width - $0.count) }
        let known: Set<String> = ["url", "urls", "link", "links", "website", "webseite", "webadresse", "address"]
        let inferred = rows.first?.contains { known.contains($0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()) } ?? false
        return CsvPreview(source: padded, hasHeader: hasHeader ?? inferred)
    }
}
public enum UrlNormalize {
    public static func normalize(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        guard !text.isEmpty && text.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else { return nil }
        let candidate: String
        if text.lowercased().hasPrefix("http://") || text.lowercased().hasPrefix("https://") { candidate = text }
        else if text.hasPrefix("//") { candidate = "https:" + text }
        else if !text.contains(":") && text.contains(".") && !text.hasPrefix(".") { candidate = "https://" + text }
        else { return nil }
        guard var parts = URLComponents(string: candidate), let scheme = parts.scheme?.lowercased(),
              ["https", "http"].contains(scheme), let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil else { return nil }
        parts.scheme = scheme
        return parts.url?.absoluteString
    }
}
