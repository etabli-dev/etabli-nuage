// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import UniformTypeIdentifiers

struct SourceView: View {
    @Environment(AppState.self) private var state
    let onPrepared: () -> Void

    @State private var pickingFile = false
    @State private var status: String?
    @State private var statusTone: StatusLabel.Tone = .info
    @State private var busy = false
    @State private var preview: CsvPreview?
    @State private var davPath: String = ""
    @State private var showingDav = false
    @State private var creds: WebDavCreds?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.lg) {
                    PromptHeader(["probe", "source"])

                    Card(title: "pick a local .csv", systemImage: "folder") {
                        MonoLabel("Choose a CSV from device storage. One column should hold the URLs (with or without a header row).",
                                  color: Theme.Color.faint)
                        PrimaryButton("Pick file", systemImage: "doc.text",
                                      enabled: !busy) { pickingFile = true }
                    }

                    Card(title: "fetch from Nextcloud", systemImage: "cloud") {
                        if let c = creds {
                            MonoLabel("Auth as \(c.username) via app password.",
                                      color: Theme.Color.faint)
                        } else {
                            MonoLabel("Set Nextcloud credentials in Settings first.",
                                      color: Theme.Color.faint)
                        }
                        PrimaryButton("Fetch from WebDAV", systemImage: "icloud.and.arrow.down",
                                      enabled: !busy && creds != nil) { showingDav = true }
                    }

                    if let preview {
                        ColumnMapCard(preview: preview, onChoose: choose)
                    }

                    if let status {
                        StatusLabel(status, tone: statusTone)
                    }
                }
                .padding(Theme.Space.lg)
            }
            .background(Theme.Color.paper.ignoresSafeArea())
            .navigationBarHidden(true)
            .fileImporter(isPresented: $pickingFile,
                          allowedContentTypes: [.commaSeparatedText, .plainText, UTType("public.text")!],
                          allowsMultipleSelection: false) { result in
                Task { await ingestPickerResult(result) }
            }
            .sheet(isPresented: $showingDav) {
                DavFetchSheet(davPath: $davPath,
                              creds: creds,
                              onFetch: { path in
                    Task { await fetchFromDav(path: path) }
                })
                .presentationDetents([.medium])
            }
            .task {
                creds = readCreds()
            }
        }
    }

    private func choose(_ columnIndex: Int) {
        guard let preview else { return }
        let raws = preview.column(columnIndex)
        let entries = raws.enumerated().map { (i, raw) in
            UrlEntry(rowIndex: i + 1, raw: raw, normalized: UrlNormalize.normalize(raw))
        }
        state.prepared = entries
        let valid = entries.filter(\.valid).count
        status = "\(valid) valid of \(entries.count) prepared. Switching to Run."
        statusTone = .accent
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { onPrepared() }
    }

    private func ingestPickerResult(_ result: Result<[URL], Error>) async {
        busy = true; defer { busy = false }
        do {
            let urls = try result.get()
            guard let url = urls.first else { return }
            let started = url.startAccessingSecurityScopedResource()
            defer { if started { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            await MainActor.run { preview = CsvLoader.parse(data) }
        } catch {
            await MainActor.run {
                status = "⚠ \(error.localizedDescription)"
                statusTone = .danger
            }
        }
    }

    private func fetchFromDav(path: String) async {
        guard let creds, !path.isEmpty else { return }
        busy = true; defer { busy = false }
        do {
            let client = WebDavClient(creds)
            let data = try await client.get(path)
            await MainActor.run { preview = CsvLoader.parse(data) }
        } catch {
            await MainActor.run {
                status = "⚠ \(error.localizedDescription)"
                statusTone = .danger
            }
        }
    }
}

// MARK: - ColumnMap

private struct ColumnMapCard: View {
    let preview: CsvPreview
    let onChoose: (Int) -> Void
    @State private var selected: Int?

    var body: some View {
        Card(title: "pick the URL column", systemImage: "tablecells") {
            VStack(alignment: .leading, spacing: Theme.Space.sm) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Theme.Space.sm) {
                        ForEach(Array(preview.headers.enumerated()), id: \.offset) { idx, h in
                            ChipButton(label: h, selected: selected == idx) {
                                selected = idx
                            }
                        }
                    }
                }
                if let s = selected {
                    let raws = preview.column(s)
                    let valid = raws.compactMap(UrlNormalize.normalize).count
                    MonoLabel("\(valid) valid of \(raws.count) rows.",
                              color: valid == 0 ? Theme.Color.danger : Theme.Color.faint)
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(0..<min(raws.count, 6), id: \.self) { i in
                            Text(raws[i].isEmpty ? "(blank)" : raws[i])
                                .font(Theme.Font.mono)
                                .foregroundStyle(Theme.Color.ink)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                    PrimaryButton("Continue", systemImage: "arrow.right",
                                  enabled: valid > 0) { onChoose(s) }
                }
            }
        }
        .onAppear {
            // Pre-select the first column whose first non-empty value looks like a URL.
            if selected == nil {
                for i in 0..<preview.headers.count {
                    if preview.column(i).contains(where: { UrlNormalize.normalize($0) != nil }) {
                        selected = i; break
                    }
                }
            }
        }
    }
}

// MARK: - DAV fetch sheet

private struct DavFetchSheet: View {
    @Binding var davPath: String
    let creds: WebDavCreds?
    let onFetch: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Theme.Space.lg) {
                PromptHeader(["probe", "fetch"])
                Card(title: "WebDAV path", systemImage: "doc.text") {
                    MonoLabel("Path relative to your Nextcloud root.", color: Theme.Color.faint)
                    TextField("links/probe.csv", text: $davPath)
                        .font(Theme.Font.mono)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(.horizontal, Theme.Space.md)
                        .padding(.vertical, Theme.Space.sm)
                        .background(Theme.Color.paper)
                        .overlay(
                            RoundedRectangle(cornerRadius: Theme.Radius.sm)
                                .stroke(Theme.Color.hairline)
                        )
                }
                HStack {
                    PrimaryButton("Cancel", systemImage: "xmark") { dismiss() }
                    Spacer()
                    PrimaryButton("Fetch", systemImage: "arrow.down.circle",
                                  enabled: !davPath.isEmpty) {
                        onFetch(davPath); dismiss()
                    }
                }
                Spacer()
            }
            .padding(Theme.Space.lg)
            .background(Theme.Color.paper.ignoresSafeArea())
        }
    }
}

// MARK: - Chip

private struct ChipButton: View {
    let label: String; let selected: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Theme.Font.caption.weight(.semibold))
                .foregroundStyle(selected ? Theme.Color.accent : Theme.Color.faint)
                .padding(.horizontal, Theme.Space.md)
                .padding(.vertical, Theme.Space.sm)
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

// MARK: - Creds reader (kept inline so SourceView doesn't pull in a dep)

func readCreds() -> WebDavCreds? {
    let base = Keychain.get(account: "nc.baseURL") ?? UserDefaults.standard.string(forKey: "nc.baseURL")
    let user = Keychain.get(account: "nc.username") ?? UserDefaults.standard.string(forKey: "nc.username")
    let pass = Keychain.get(account: "nc.password")
    guard let base, let user, let pass, !base.isEmpty, !user.isEmpty, !pass.isEmpty
    else { return nil }
    return WebDavCreds(baseURL: base, username: user, appPassword: pass)
}
