// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

struct RootView: View {
    @Environment(AppState.self) private var state
    @State private var tab: Int = 0

    var body: some View {
        TabView(selection: $tab) {
            SourceView(onPrepared: { tab = 1 })
                .tabItem { Label("Source", systemImage: "doc.text.below.ecg") }
                .tag(0)

            RunView(onSeeResults: { tab = 2 })
                .tabItem { Label("Run", systemImage: "play.fill") }
                .tag(1)

            ResultsView()
                .tabItem { Label("Results", systemImage: "tablecells") }
                .tag(2)

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(3)
        }
    }
}
