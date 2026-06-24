// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

struct ResultsView: View {
    @Environment(AppState.self) private var state
    enum Filter: String, CaseIterable, Identifiable {
        case all, pass, fail
        var id: String { rawValue }
    }
    @State private var filter: Filter = .all
    @State private var status: String?
    @State private var statusTone: StatusLabel.Tone = .info
    @State private var sharing: ActivityShareItem?

    private var allResults: [CheckResult] { state.logger?.memory ?? [] }
    private var filtered: [CheckResult] {
        switch filter {
        case .all:  return allResults
        case .pass: return allResults.filter(\.pass)
        case .fail: return allResults.filter { !$0.pass }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.lg) {
                PromptHeader(["probe", "results", "\(allResults.count)"])

                Card(title: "totals", systemImage: "chart.bar") {
                    HStack(spacing: Theme.Space.xl) {
                        statTile("total", "\(allResults.count)", color: Theme.Color.ink)
                        statTile("pass",  "\(allResults.filter(\.pass).count)", color: Theme.Color.accent)
                        statTile("fail",  "\(allResults.filter { !$0.pass }.count)", color: Theme.Color.danger)
                    }
                }

                HStack {
                    ForEach(Filter.allCases) { f in
                        FilterChip(label: f.rawValue, selected: filter == f) { filter = f }
                    }
                }

                if filtered.isEmpty {
                    Card(title: "empty") {
                        MonoLabel("No results yet. Start a run.", color: Theme.Color.faint)
                    }
                } else {
                    Card(title: "log", systemImage: "tablecells") {
                        ForEach(Array(filtered.prefix(200))) { r in
                            ResultRow(r: r)
                            Divider().background(Theme.Color.hairline)
                        }
                        if filtered.count > 200 {
                            MonoLabel("… \(filtered.count - 200) more (export to see all).",
                                      color: Theme.Color.faint)
                        }
                    }
                }

                HStack(spacing: Theme.Space.md) {
                    PrimaryButton("Share CSV", systemImage: "square.and.arrow.up",
                                  enabled: state.logger?.currentURL != nil) { shareCSV() }
                    PrimaryButton("Upload to Nextcloud", systemImage: "cloud.fill",
                                  enabled: state.logger?.currentURL != nil) {
                        Task { await uploadToNextcloud() }
                    }
                }

                if let status { StatusLabel(status, tone: statusTone) }
            }
            .padding(Theme.Space.lg)
        }
        .background(Theme.Color.paper.ignoresSafeArea())
        .sheet(item: $sharing) { item in
            ActivityShareView(item: item)
        }
    }

    private func statTile(_ label: String, _ value: String, color: Color) -> some View {
        VStack {
            Text(value).font(Theme.Font.display).foregroundStyle(color)
            MonoLabel(label, color: Theme.Color.faint)
        }
    }

    private func shareCSV() {
        guard let url = state.logger?.currentURL else { return }
        sharing = ActivityShareItem(url: url)
    }

    private func uploadToNextcloud() async {
        guard let url = state.logger?.currentURL,
              let c = readCreds() else {
            status = "⚠ Set Nextcloud credentials first."; statusTone = .danger; return
        }
        do {
            let data = try Data(contentsOf: url)
            let dir = UserDefaults.standard.string(forKey: "nc.uploadDir") ?? "EtabliProbe"
            let name = url.lastPathComponent
            let client = WebDavClient(c)
            try await client.put(directory: dir, file: name, data: data,
                                 contentType: "text/csv; charset=utf-8")
            status = "Uploaded to \(dir)/\(name)"; statusTone = .accent
        } catch {
            status = "⚠ \(error.localizedDescription)"; statusTone = .danger
        }
    }
}

private struct ResultRow: View {
    let r: CheckResult
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                StatusLabel(r.result, tone: r.pass ? .accent : .danger)
                MonoLabel("#\(r.rowIndex)", color: Theme.Color.faint)
                Spacer()
                MonoLabel("\(r.durationMs.map(String.init) ?? "—") ms", color: Theme.Color.faint)
            }
            Text(r.url).font(Theme.Font.mono).foregroundStyle(Theme.Color.ink)
                .lineLimit(2).truncationMode(.middle)
            if let final = r.finalURL, final != r.url {
                Text("→ \(final)").font(Theme.Font.mono)
                    .foregroundStyle(Theme.Color.faint)
                    .lineLimit(1).truncationMode(.middle)
            }
        }
        .padding(.vertical, Theme.Space.xs)
    }
}

private struct FilterChip: View {
    let label: String; let selected: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Theme.Font.caption.weight(.semibold))
                .foregroundStyle(selected ? Theme.Color.accent : Theme.Color.faint)
                .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
                .background(
                    RoundedRectangle(cornerRadius: Theme.Radius.sm)
                        .fill(selected ? Theme.Color.accentMuted : Theme.Color.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.Radius.sm)
                        .stroke(selected ? Theme.Color.accent : Theme.Color.hairline)
                )
        }.buttonStyle(.plain)
    }
}

// Share sheet wrapper.
struct ActivityShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ActivityShareView: UIViewControllerRepresentable {
    let item: ActivityShareItem
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [item.url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
