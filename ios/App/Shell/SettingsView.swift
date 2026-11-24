// Copyright 2026 R. Heller
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

struct SettingsView: View {
    @Environment(AppState.self) private var state
    @AppStorage("cfg.mode") private var modeRaw: String = RunMode.foreground.rawValue
    @AppStorage("cfg.delaySec") private var delaySec: Double = 1.0
    @AppStorage("cfg.timeoutSec") private var timeoutSec: Double = 15.0
    @AppStorage("cfg.rotation") private var rotationRaw: String = LogRotation.append.rawValue
    @AppStorage("nc.uploadDir") private var uploadDir: String = "EtabliProbe"
    @AppStorage("cfg.maskMode") private var maskRaw: String = MaskMode.none.rawValue

    // Plaintext base URL + username live in UserDefaults; password in Keychain.
    @AppStorage("nc.baseURL") private var baseURL: String = "https://"
    @AppStorage("nc.username") private var username: String = ""

    @State private var savedIdentity: WebDavCreds?
    @State private var passwordField: String = ""
    @State private var busy = false
    @State private var status: String?
    @State private var statusTone: StatusLabel.Tone = .info
    @State private var profile: UsageProfile?
    @State private var profileStatus: String?
    @State private var profileTone: StatusLabel.Tone = .info
    @State private var importing: Bool = false

    private var modeBlurb: LocalizedStringKey {
        switch RunMode(rawValue: modeRaw) ?? .foreground {
        case .foreground: return "each URL renders in a real WKWebView so you can watch it load."
        case .background: return "each URL is probed with URLSession (HTTP GET, follows redirects)."
        case .scheduled:  return "URLs are opened from a usage-profile-derived per-minute probability (card below)."
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.lg) {
                PromptHeader(["probe", "settings"])

                Card(title: "check mode", systemImage: "arrow.left.arrow.right") {
                    HStack {
                        ForEach(RunMode.allCases) { m in
                            FilterChip(label: m.title, selected: modeRaw == m.rawValue) {
                                modeRaw = m.rawValue
                            }
                        }
                    }
                    MonoLabel(modeBlurb, color: Theme.Color.faint)
                }

                Card(title: "usage profile", systemImage: "chart.bar.xaxis") {
                    MonoLabel(
                        "Drive the 'scheduled' run mode from real smartphone use. Import a TXT log of app open/close events with timestamps; the parser builds a 1440-element minute-of-day probability profile by averaging over every distinct day in the file.",
                        color: Theme.Color.faint,
                    )
                    if let p = profile, !p.isEmpty {
                        MonoLabel("source · \(p.sourceName ?? "—")", color: Theme.Color.ink)
                        MonoLabel("records · \(p.totalRecords) parsed (\(p.skippedLines) skipped)",
                                  color: Theme.Color.faint)
                        MonoLabel("days · \(p.totalDays) distinct", color: Theme.Color.faint)
                        MonoLabel("peak · \(UsageProfile.hhmm(p.peakMinute)) at p=\(String(format: "%.3f", p.perMinute[p.peakMinute]))",
                                  color: Theme.Color.faint)
                        MonoLabel("mean · \(String(format: "%.3f", p.mean))",
                                  color: Theme.Color.faint)
                    } else {
                        MonoLabel("no profile loaded.", color: Theme.Color.faint)
                    }
                    HStack {
                        PrimaryButton(importing ? "Importing…" : "Import .txt",
                                      systemImage: "tray.and.arrow.down",
                                      enabled: !importing) { importing = true }
                        PrimaryButton("Clear", systemImage: "xmark.circle") {
                            UsageProfileStore.clear(); profile = nil
                            profileStatus = String(localized: "Profile cleared."); profileTone = .info
                        }
                    }
                    if let profileStatus {
                        StatusLabel(profileStatus, tone: profileTone)
                    }
                }

                Card(title: "access mask", systemImage: "calendar.badge.clock") {
                    MonoLabel(
                        "Multiplies the profile probability by 0 outside the chosen window. Worktime = Mon-Fri 08:00..18:00. Off-hours = everything else.",
                        color: Theme.Color.faint,
                    )
                    HStack {
                        ForEach(MaskMode.allCases) { m in
                            FilterChip(label: m.title, selected: maskRaw == m.rawValue) {
                                maskRaw = m.rawValue
                            }
                        }
                    }
                }

                Card(title: "politeness", systemImage: "timer") {
                    HStack {
                        MonoLabel("request delay")
                        Spacer()
                        MonoLabel(String(format: "%.1f s", delaySec))
                    }
                    Slider(value: $delaySec, in: 0.1...10, step: 0.1)
                    HStack {
                        MonoLabel("timeout")
                        Spacer()
                        MonoLabel("\(Int(timeoutSec)) s")
                    }
                    Slider(value: $timeoutSec, in: 3...120, step: 1)
                }

                Card(title: "Nextcloud (CSV fetch + log upload)", systemImage: "cloud") {
                    MonoLabel("Use an APP PASSWORD (Settings → Security → Devices & sessions in Nextcloud). NOT your login.",
                              color: Theme.Color.faint)
                    LabeledField("base URL", text: $baseURL)
                    LabeledField("username", text: $username)
                    LabeledField("app password", text: $passwordField, secure: true)
                    LabeledField("upload folder", text: $uploadDir)

                    HStack {
                        PrimaryButton(busy ? "Testing…" : "Save & test", systemImage: "checkmark.seal",
                                      enabled: !busy) { Task { await testConnection() } }
                        PrimaryButton("Disconnect", systemImage: "xmark.circle") { clear() }
                    }
                    if let status { StatusLabel(status, tone: statusTone) }
                }

                Card(title: "log", systemImage: "doc.text") {
                    HStack {
                        ForEach(LogRotation.allCases) { r in
                            FilterChip(label: r.title, selected: rotationRaw == r.rawValue) {
                                rotationRaw = r.rawValue
                            }
                        }
                    }
                }

                AppearanceCard()

                Card(title: "about", systemImage: "info.circle") {
                    Text("Établi Probe — Coder-suite link-checker.").font(Theme.Font.body)
                    MonoLabel("install id (NOT a hardware/device id):", color: Theme.Color.faint)
                    MonoLabel(state.installID, color: Theme.Color.faint)
                }
            }
            .padding(Theme.Space.lg).readableColumn()
        }
        .background(Theme.Color.paper.ignoresSafeArea())
        .onAppear {
            savedIdentity = readCreds()
            profile = UsageProfileStore.load()
        }
        .fileImporter(isPresented: $importing,
                      allowedContentTypes: [.plainText, .text, .data],
                      allowsMultipleSelection: false) { result in
            handleImport(result)
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let opened = url.startAccessingSecurityScopedResource()
            defer { if opened { url.stopAccessingSecurityScopedResource() } }
            let data = try CsvLoader.read(url)
            let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .isoLatin1)
                ?? ""
            let parsed = try UsageProfileParser.parse(text, sourceName: url.lastPathComponent)
            try UsageProfileStore.save(parsed)
            profile = parsed
            if parsed.totalDays == 0 {
                profileStatus = String(localized: "⚠ no valid open/close events recognised (\(parsed.skippedLines) lines skipped).")
                profileTone = .danger
            } else {
                profileStatus = String(localized: "Loaded \(parsed.totalRecords) events across \(parsed.totalDays) days.")
                profileTone = .success
            }
        } catch {
            profileStatus = String(localized: "⚠ \(error.localizedDescription)"); profileTone = .danger
        }
    }

    private func testConnection() async {
        busy = true; defer { busy = false }
        status = nil
        let pw: String
        if passwordField.isEmpty, let saved = savedIdentity, saved.baseURL == baseURL.trimmingCharacters(in: .whitespaces), saved.username == username.trimmingCharacters(in: .whitespaces) {
            pw = saved.appPassword
        } else {
            pw = passwordField
        }
        guard !baseURL.isEmpty, !username.isEmpty, !pw.isEmpty else {
            status = String(localized: "⚠ Fill in all three fields."); statusTone = .danger; return
        }
        let creds = WebDavCreds(baseURL: baseURL.trimmingCharacters(in: .whitespaces),
                                username: username.trimmingCharacters(in: .whitespaces),
                                appPassword: pw)
        do {
            let count = try await WebDavClient(creds).testConnection()
            let saved = try JSONEncoder().encode(creds)
            try Keychain.set(String(decoding: saved, as: UTF8.self), account: "nc.credentials.v2")
            passwordField = ""
            savedIdentity = creds
            status = String(localized: "OK — \(count) items at root. Credentials stored in Keychain.")
            statusTone = .success
        } catch {
            status = String(localized: "⚠ \(error.localizedDescription)"); statusTone = .danger
        }
    }

    private func clear() {
        do {
            for account in ["nc.password", "nc.baseURL", "nc.username", "nc.credentials.v2"] { try Keychain.delete(account: account) }
            passwordField = ""; savedIdentity = nil
            status = String(localized: "Disconnected — Keychain entries removed."); statusTone = .info
        } catch { status = String(localized: "Could not remove credentials: \(error.localizedDescription)"); statusTone = .danger }
    }
}

private struct LabeledField: View {
    let label: LocalizedStringKey
    @Binding var text: String
    var secure: Bool = false
    init(_ label: LocalizedStringKey, text: Binding<String>, secure: Bool = false) {
        self.label = label; self._text = text; self.secure = secure
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            MonoLabel(label, color: Theme.Color.faint)
            Group {
                if secure { SecureField("", text: $text) }
                else { TextField("", text: $text)
                    .textInputAutocapitalization(.never).autocorrectionDisabled() }
            }
            .font(Theme.Font.body)
            .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
            .background(Theme.Color.paper)
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Radius.sm).stroke(Theme.Color.hairline)
            )
        }
    }
}

private struct FilterChip: View {
    @Environment(\.themeAccent) private var accent
    @Environment(\.themeSelectionTint) private var selectionTint
    let label: LocalizedStringKey; let selected: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Theme.Font.caption.weight(.semibold))
                .foregroundStyle(selected ? accent : Theme.Color.faint)
                .padding(.horizontal, Theme.Space.md).padding(.vertical, Theme.Space.sm)
                .background(
                    RoundedRectangle(cornerRadius: Theme.Radius.sm)
                        .fill(selected ? selectionTint.opacity(AccentChoice.selectionOpacity) : Theme.Color.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.Radius.sm)
                        .stroke(selected ? accent : Theme.Color.hairline)
                )
        }.buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// Chip titles as localized keys, so the in-app language applies at once.
extension RunMode {
    var title: LocalizedStringKey {
        switch self {
        case .foreground: "foreground"
        case .background: "background"
        case .scheduled: "scheduled"
        }
    }
}

extension MaskMode {
    var title: LocalizedStringKey {
        switch self {
        case .none: "off"
        case .worktime: "worktime only"
        case .offhours: "off-hours only"
        }
    }
}

extension LogRotation {
    var title: LocalizedStringKey { self == .append ? "append" : "per run" }
}
