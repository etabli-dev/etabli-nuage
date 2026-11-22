// Copyright 2026 R. Heller
// SPDX-License-Identifier: LicenseRef-Proprietary

import SwiftUI

@main
struct EtabliProbeApp: App {
    @AppStorage(ThemePreference.userDefaultsKey) private var themeRaw: String = ThemePreference.system.rawValue
    @State private var state = AppState.shared

    private var theme: ThemePreference {
        ThemePreference(rawValue: themeRaw) ?? .system
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(state)
                .preferredColorScheme(theme.colorScheme)
                .tint(Theme.Color.accent)
        }
    }
}
