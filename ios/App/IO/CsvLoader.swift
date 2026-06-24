// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// CSV loader. Accepts comma- OR semicolon-separated (the two real-world
// dialects you hit when "Save as CSV" comes from US- vs DE-locale Excel).
// Handles double-quoted fields with escaped quotes.

public struct CsvPreview {
    public let headers: [String]
    public let rows: [[String]]
    public func column(_ idx: Int) -> [String] {
        rows.map { idx < $0.count ? $0[idx] : "" }
    }
}

public enum CsvLoader {
    public static func parse(_ data: Data) -> CsvPreview {
        let text = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        let delim = detectDelimiter(text)
        let rows = tokenize(text, delim: delim)
        guard let first = rows.first, !rows.isEmpty else {
            return CsvPreview(headers: [], rows: [])
        }
        // Pad rows to the widest row.
        let cols = rows.map(\.count).max() ?? first.count
        let padded = rows.map { r -> [String] in
            r + Array(repeating: "", count: max(0, cols - r.count))
        }
        if looksLikeHeader(padded[0]) {
            let headers = padded[0].map { $0.isEmpty ? "(blank)" : $0 }
            return CsvPreview(headers: headers, rows: Array(padded.dropFirst()))
        } else {
            let headers = (1...cols).map { "col \($0)" }
            return CsvPreview(headers: headers, rows: padded)
        }
    }

    private static func detectDelimiter(_ text: String) -> Character {
        let head = text.prefix(8192)
        let commas = head.filter { $0 == "," }.count
        let semis  = head.filter { $0 == ";" }.count
        let tabs   = head.filter { $0 == "\t" }.count
        let scored: [(Character, Int)] = [(",", commas), (";", semis), ("\t", tabs)]
        return scored.max(by: { $0.1 < $1.1 })?.0 ?? ","
    }

    private static func looksLikeHeader(_ row: [String]) -> Bool {
        let nonEmpty = row.filter { !$0.isEmpty }
        guard !nonEmpty.isEmpty else { return false }
        let headerish = nonEmpty.filter { v in
            let lower = v.lowercased()
            let url = lower.hasPrefix("http://") || lower.hasPrefix("https://") || v.contains("://")
            let num = Double(v) != nil
            return !url && !num && v.count <= 64
        }.count
        return Double(headerish) / Double(nonEmpty.count) >= 0.6
    }

    // RFC-4180-ish tokenizer: handles double-quoted fields, escaped quotes,
    // and CR/LF line endings.
    private static func tokenize(_ text: String, delim: Character) -> [[String]] {
        var rows: [[String]] = []
        var current: [String] = []
        var field = ""
        var inQuotes = false
        var i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if inQuotes {
                if c == "\"" {
                    let next = text.index(after: i)
                    if next < text.endIndex, text[next] == "\"" {
                        field.append("\"")
                        i = text.index(after: next); continue
                    }
                    inQuotes = false
                } else { field.append(c) }
            } else {
                switch c {
                case "\"":
                    inQuotes = true
                case delim:
                    current.append(field); field = ""
                case "\r":
                    // swallow; rely on \n to commit row
                    break
                case "\n":
                    current.append(field); field = ""
                    rows.append(current); current = []
                default:
                    field.append(c)
                }
            }
            i = text.index(after: i)
        }
        if !field.isEmpty || !current.isEmpty {
            current.append(field); rows.append(current)
        }
        return rows
    }
}

// MARK: - URL normalisation
public enum UrlNormalize {
    public static func normalize(_ raw: String) -> String? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                   .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        guard !t.isEmpty else { return nil }
        let candidate: String
        let lower = t.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            candidate = t
        } else if t.hasPrefix("//") {
            candidate = "https:\(t)"
        } else if t.contains(" ") || t.contains("\n") {
            return nil
        } else if t.contains(".") && !t.hasPrefix(".") {
            candidate = "https://\(t)"
        } else { return nil }
        guard let url = URL(string: candidate), let s = url.scheme,
              (s == "http" || s == "https"), url.host != nil else { return nil }
        return url.absoluteString
    }
}
