// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Shared data models for the link-checker. Pure value types — easy to thread
// across the URLSession / WKWebView async boundaries.

public struct UrlEntry: Hashable, Sendable, Identifiable {
    public let rowIndex: Int          // 1-based source row (header stripped)
    public let raw: String
    public let normalized: String?
    public var id: Int { rowIndex }
    public var valid: Bool { normalized != nil }
}

public struct CheckResult: Hashable, Sendable, Identifiable {
    public let rowIndex: Int
    public let url: String
    public let mode: String           // "foreground" | "background"
    public let result: String         // "200" | "FAILED: …" | "LOADED" | …
    public let finalURL: String?
    public let durationMs: Int?
    public let timestampLocal: Date
    public let timestampUtc: Date
    public let installId: String
    public var id: String { "\(rowIndex)-\(timestampUtc.timeIntervalSince1970)" }

    public var pass: Bool {
        if mode == "foreground" { return result == "LOADED" }
        if let code = Int(result) { return (200..<400).contains(code) }
        return false
    }

    public static let csvHeader = [
        "row_index","url","mode","result","final_url",
        "duration_ms","timestamp_local","timestamp_utc","install_id",
    ]

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    public func csvRow() -> [String] {
        [
            String(rowIndex),
            url,
            mode,
            result,
            finalURL ?? "",
            durationMs.map(String.init) ?? "",
            CheckResult.isoFormatter.string(from: timestampLocal),
            CheckResult.isoFormatter.string(from: timestampUtc),
            installId,
        ]
    }
}

public enum RunMode: String, CaseIterable, Identifiable, Sendable {
    case foreground, background, scheduled
    public var id: String { rawValue }
    public var label: String { rawValue }
}

public enum LogRotation: String, CaseIterable, Identifiable, Sendable {
    case append, perRun
    public var id: String { rawValue }
    public var label: String { self == .append ? "append" : "per run" }
}

public struct WebDavCreds: Equatable, Sendable {
    public let baseURL: String
    public let username: String
    public let appPassword: String
}
