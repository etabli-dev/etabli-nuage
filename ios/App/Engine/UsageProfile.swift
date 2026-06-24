// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Smartphone-use → per-minute-of-day probability profile.
//
// Input: a TXT log where each line records an app foreground/background event
// with a timestamp. The parser is intentionally tolerant — it scans each line
// for a date, a time, and an event keyword, and ignores the rest. Examples
// it accepts:
//
//   2026-04-12T08:14:22  com.google.android.gm  OPEN
//   2026-04-12 08:14:22, com.whatsapp, opened
//   1745059862 com.whatsapp open
//   2026-04-12T08:14:22Z opened  app=com.example
//   12/04/2026 8:14 com.foo started
//
// Algorithm (the "month-level average" the user asked for):
//   1. Pair OPEN events with subsequent CLOSE events for the same key, build
//      intervals. Lonely OPENs become 1-minute sessions; lonely CLOSEs are
//      ignored.
//   2. For each interval, mark every minute it covers as "active" for that
//      (calendar-date, minute-of-day) cell.
//   3. profile[m] = (# distinct dates with any interval covering minute m) /
//                   (total distinct dates in the log)
//
// The result is a 1440-element array of probabilities in [0,1] — one per
// minute of the day, averaged across all days the file covers. A file with
// roughly a month of data lands the "month-level average" naturally.

public struct UsageProfile: Codable, Equatable, Sendable {
    public var perMinute: [Double]              // length 1440
    public var totalRecords: Int
    public var totalDays: Int
    public var skippedLines: Int
    public var sourceName: String?              // friendly filename for the UI

    public init(perMinute: [Double] = Array(repeating: 0, count: 1440),
                totalRecords: Int = 0,
                totalDays: Int = 0,
                skippedLines: Int = 0,
                sourceName: String? = nil) {
        self.perMinute = perMinute
        self.totalRecords = totalRecords
        self.totalDays = totalDays
        self.skippedLines = skippedLines
        self.sourceName = sourceName
    }

    public var isEmpty: Bool { totalDays == 0 || perMinute.allSatisfy { $0 == 0 } }
    public var peakMinute: Int { perMinute.indices.max(by: { perMinute[$0] < perMinute[$1] }) ?? 0 }
    public var mean: Double { perMinute.reduce(0, +) / Double(perMinute.count) }

    /// Probability at a given minute, with the mask applied.
    public func probability(forMinute minute: Int, weekday: Int, mask: MaskMode) -> Double {
        let mod = ((minute % 1440) + 1440) % 1440
        let base = perMinute[mod]
        switch mask {
        case .none:     return base
        case .worktime: return isWorktime(minuteOfDay: mod, weekday: weekday) ? base : 0
        case .offhours: return isWorktime(minuteOfDay: mod, weekday: weekday) ? 0 : base
        }
    }

    /// Mon-Fri 08:00..17:59. Weekday uses Calendar.weekday convention
    /// (1=Sun..7=Sat) so callers can pass `Calendar.current.component(.weekday, …)`.
    public static func isWorktime(minuteOfDay: Int, weekday: Int) -> Bool {
        let isWeekday = (2...6).contains(weekday)
        let hour = minuteOfDay / 60
        return isWeekday && hour >= 8 && hour < 18
    }
    private func isWorktime(minuteOfDay: Int, weekday: Int) -> Bool {
        Self.isWorktime(minuteOfDay: minuteOfDay, weekday: weekday)
    }

    /// Pretty-format a minute of day as HH:MM.
    public static func hhmm(_ minute: Int) -> String {
        String(format: "%02d:%02d", minute / 60, minute % 60)
    }
}

public enum MaskMode: String, CaseIterable, Identifiable, Sendable {
    case none, worktime, offhours
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .none:     return "off"
        case .worktime: return "worktime only"
        case .offhours: return "off-hours only"
        }
    }
}

// MARK: - Parser

public enum UsageProfileParser {

    /// Parse a TXT log and aggregate to a UsageProfile.
    public static func parse(_ text: String, sourceName: String? = nil) -> UsageProfile {
        let lines = text.split(whereSeparator: { $0 == "\n" || $0 == "\r" })
        var records: [(date: Date, key: String, event: Event)] = []
        var skipped = 0
        for raw in lines {
            let line = String(raw).trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            guard let r = parseLine(line) else { skipped += 1; continue }
            records.append(r)
        }
        records.sort { $0.date < $1.date }

        // Pair OPEN/CLOSE into intervals.
        var openMap: [String: Date] = [:]
        var sessions: [(Date, Date)] = []
        for r in records {
            switch r.event {
            case .open:
                openMap[r.key] = r.date
            case .close:
                if let opened = openMap.removeValue(forKey: r.key), opened <= r.date {
                    sessions.append((opened, r.date))
                }
                // Bare CLOSE is dropped intentionally; we have no idea when it started.
            }
        }
        // Lonely OPENs → 1-minute sessions.
        for (_, opened) in openMap {
            sessions.append((opened, opened.addingTimeInterval(60)))
        }

        // Build per-day per-minute activity set.
        let cal = Calendar(identifier: .gregorian)
        var perDayActive: [Date: Set<Int>] = [:]
        var allDays: Set<Date> = []
        for (start, end) in sessions {
            var cur = start
            while cur < end {
                let day = cal.startOfDay(for: cur)
                let m = cal.component(.hour, from: cur) * 60 + cal.component(.minute, from: cur)
                perDayActive[day, default: []].insert(m)
                allDays.insert(day)
                cur = cur.addingTimeInterval(60)
            }
        }

        let totalDays = allDays.count
        var profile = Array(repeating: 0.0, count: 1440)
        if totalDays > 0 {
            for (_, mins) in perDayActive {
                for m in mins where m >= 0 && m < 1440 {
                    profile[m] += 1
                }
            }
            for i in 0..<1440 { profile[i] /= Double(totalDays) }
        }

        return UsageProfile(perMinute: profile,
                            totalRecords: records.count,
                            totalDays: totalDays,
                            skippedLines: skipped,
                            sourceName: sourceName)
    }

    enum Event { case open, close }

    // Per-line scanner: extract a timestamp + event keyword + optional key.
    private static func parseLine(_ s: String) -> (date: Date, key: String, event: Event)? {
        let lower = s.lowercased()
        // Event keyword
        let event: Event
        if let _ = lower.range(of: "(^|\\W)(open|opened|launch|launched|start|started|foreground|fg|resume)(\\W|$)",
                               options: .regularExpression) {
            event = .open
        } else if let _ = lower.range(of: "(^|\\W)(close|closed|stop|stopped|background|bg|pause|exit)(\\W|$)",
                                      options: .regularExpression) {
            event = .close
        } else {
            return nil
        }

        // Timestamp
        guard let date = scanTimestamp(s) else { return nil }

        // Package-ish key (dotted identifier, or just a bare word like "whatsapp").
        let key = scanKey(s) ?? "_any"
        return (date, key, event)
    }

    private static func scanTimestamp(_ s: String) -> Date? {
        // 1. ISO-ish dates with T or space separator.
        let isoFormats = [
            "yyyy-MM-dd'T'HH:mm:ssXXXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
        ]
        // Try to extract a candidate substring around recognised date shapes.
        let candidates = candidateSubstrings(s)
        for cand in candidates {
            for fmt in isoFormats {
                let df = DateFormatter()
                df.locale = Locale(identifier: "en_US_POSIX")
                df.dateFormat = fmt
                if let d = df.date(from: cand) { return d }
            }
            // ISO8601 with formatter (handles fractional seconds, Z)
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = iso.date(from: cand) { return d }
            iso.formatOptions = [.withInternetDateTime]
            if let d = iso.date(from: cand) { return d }
        }
        // Unix epoch (10-digit seconds, or 13-digit ms)
        if let m = s.range(of: "(?<![0-9])([0-9]{13})(?![0-9])", options: .regularExpression),
           let ms = Int64(s[m]) {
            return Date(timeIntervalSince1970: Double(ms) / 1000)
        }
        if let m = s.range(of: "(?<![0-9])([0-9]{10})(?![0-9])", options: .regularExpression),
           let sec = Int64(s[m]) {
            return Date(timeIntervalSince1970: Double(sec))
        }
        return nil
    }

    // Pull out plausible "date + time" substrings to try against the format list.
    private static func candidateSubstrings(_ s: String) -> [String] {
        var results: [String] = []
        let patterns = [
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?",
            "\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}(:\\d{2})?",
            "\\d{2}/\\d{2}/\\d{4} \\d{1,2}:\\d{2}(:\\d{2})?",
        ]
        for pat in patterns {
            var search = s.startIndex..<s.endIndex
            while let r = s.range(of: pat, options: .regularExpression, range: search) {
                results.append(String(s[r]))
                search = r.upperBound..<s.endIndex
            }
        }
        return results
    }

    // Pull out a dotted package id (preferred); else the first word that isn't
    // a date / time / event keyword.
    private static func scanKey(_ s: String) -> String? {
        if let r = s.range(of: "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+",
                           options: .regularExpression) {
            return String(s[r])
        }
        return nil
    }
}

// MARK: - On-disk persistence (Documents/probe_profile.json)

public enum UsageProfileStore {
    private static var url: URL {
        FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("probe_profile.json")
    }
    public static func save(_ p: UsageProfile) {
        if let data = try? JSONEncoder().encode(p) {
            try? data.write(to: url, options: .atomic)
        }
    }
    public static func load() -> UsageProfile? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(UsageProfile.self, from: data)
    }
    public static func clear() { try? FileManager.default.removeItem(at: url) }
}
