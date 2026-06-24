// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Observation

// Process-wide state — single source of truth for the active run, prepared
// URL list, in-memory log mirror, and the per-install UUID.
// @Observable so SwiftUI views just read properties and refresh automatically.

@Observable
@MainActor
public final class AppState {
    public static let shared = AppState()

    // Persisted per-install UUID. Stored in Keychain so an iCloud-Keychain-
    // synced reinstall (same Apple ID, same device) keeps the ID; a true
    // uninstall + reinstall fresh-install produces a new one. Explicitly NOT
    // a hardware identifier — Apple removed those years ago.
    public let installID: String

    // Set by SourceView once a column has been selected.
    public var prepared: [UrlEntry] = []

    // Current run job — owned by RunView, mirrored here for cross-tab reads.
    public var logger: CheckLogger?
    public var done: Int = 0
    public var total: Int = 0
    public var passCount: Int = 0
    public var failCount: Int = 0
    public var running: Bool = false
    public var paused: Bool = false

    public init() {
        let account = "install.id"
        if let existing = Keychain.get(account: account) {
            self.installID = existing
        } else {
            let new = UUID().uuidString
            try? Keychain.set(new, account: account)
            self.installID = new
        }
    }
}
