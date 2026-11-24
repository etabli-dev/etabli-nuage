// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

@main
struct EtabliProbeApp: App {
    @State private var state = AppState.shared

    init() { AppearancePreference.migrateLegacyMode(in: .standard) }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(state)
                .etabliAppearance()
        }
    }
}
