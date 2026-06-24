// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Persisted queue + cursor — survives kills so a background run resumes
// where it left off. JSON in the app's Documents folder.

public struct QueueSnapshot: Codable, Sendable {
    public var entries: [Item]
    public var cursor: Int
    public var updatedAt: Date
    public struct Item: Codable, Sendable {
        public let row: Int
        public let raw: String
        public let normalized: String?
    }
}

public enum QueueStore {
    private static var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("probe_queue.json")
    }
    public static func save(entries: [UrlEntry], cursor: Int) throws {
        let snap = QueueSnapshot(
            entries: entries.map { .init(row: $0.rowIndex, raw: $0.raw, normalized: $0.normalized) },
            cursor: cursor,
            updatedAt: Date()
        )
        let data = try JSONEncoder().encode(snap)
        try data.write(to: fileURL, options: .atomic)
    }
    public static func load() -> (entries: [UrlEntry], cursor: Int)? {
        guard let data = try? Data(contentsOf: fileURL),
              let snap = try? JSONDecoder().decode(QueueSnapshot.self, from: data)
        else { return nil }
        let entries = snap.entries.map { UrlEntry(rowIndex: $0.row, raw: $0.raw, normalized: $0.normalized) }
        return (entries, snap.cursor)
    }
    public static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
