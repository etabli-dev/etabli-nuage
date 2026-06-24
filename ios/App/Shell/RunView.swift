// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import WebKit

struct RunView: View {
    @Environment(AppState.self) private var state
    @AppStorage("cfg.mode") private var modeRaw: String = RunMode.foreground.rawValue
    @AppStorage("cfg.delaySec") private var delaySec: Double = 1.0
    @AppStorage("cfg.timeoutSec") private var timeoutSec: Double = 15.0
    @AppStorage("cfg.rotation") private var rotationRaw: String = LogRotation.append.rawValue
    @AppStorage("cfg.maskMode") private var maskRaw: String = MaskMode.none.rawValue

    let onSeeResults: () -> Void

    // Foreground job state
    @State private var webContainer = WebContainer()
    @State private var runTask: Task<Void, Never>?
    @State private var currentMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.lg) {
                PromptHeader(["probe", "run", modeRaw])

                if state.prepared.isEmpty {
                    Card(title: "no source loaded") {
                        MonoLabel("Pick a CSV in Source first.", color: Theme.Color.faint)
                    }
                } else {
                    Card(title: "ready", systemImage: "checklist") {
                        let valid = state.prepared.filter(\.valid).count
                        MonoLabel("\(state.prepared.count) rows · \(valid) valid",
                                  color: Theme.Color.faint)
                        MonoLabel("delay \(Int(delaySec * 1000))ms · timeout \(Int(timeoutSec))s",
                                  color: Theme.Color.faint)
                    }
                }

                switch RunMode(rawValue: modeRaw) ?? .foreground {
                case .foreground: foregroundBody
                case .background: backgroundBody
                case .scheduled:  scheduledBody
                }
            }
            .padding(Theme.Space.lg)
        }
        .background(Theme.Color.paper.ignoresSafeArea())
        .onAppear { profile = UsageProfileStore.load() }
    }

    // MARK: - Foreground
    private var foregroundBody: some View {
        VStack(alignment: .leading, spacing: Theme.Space.lg) {
            if state.running || state.done > 0 {
                Card(title: "progress", systemImage: "play.fill") {
                    MonoLabel("\(state.done) of \(state.total)")
                    HStack {
                        StatusLabel("pass \(state.passCount)", tone: .accent)
                        StatusLabel("fail \(state.failCount)", tone: state.failCount > 0 ? .danger : .info)
                    }
                    if let currentMessage {
                        MonoLabel(currentMessage, color: Theme.Color.faint)
                    }
                }
            }

            Card(title: "webview", systemImage: "globe") {
                WebViewBox(container: webContainer)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.Radius.sm)
                            .stroke(Theme.Color.hairline)
                    )
            }

            HStack {
                if !state.running {
                    PrimaryButton("Start", systemImage: "play.fill",
                                  enabled: state.prepared.contains(where: \.valid)) { startForeground() }
                } else {
                    PrimaryButton(state.paused ? "Resume" : "Pause",
                                  systemImage: state.paused ? "play.fill" : "pause.fill") {
                        state.paused.toggle()
                    }
                    PrimaryButton("Stop", systemImage: "stop.fill") { stop() }
                }
                Spacer()
                PrimaryButton("See log", systemImage: "tablecells") { onSeeResults() }
            }
        }
    }

    private func startForeground() {
        let entries = state.prepared
        let installID = state.installID
        let logger = CheckLogger(rotation: LogRotation(rawValue: rotationRaw) ?? .append)
        state.logger = logger
        state.total = entries.count
        state.done = 0; state.passCount = 0; state.failCount = 0
        state.running = true; state.paused = false
        currentMessage = nil

        let timeout = timeoutSec
        let delay = delaySec
        let container = webContainer

        runTask = Task { @MainActor in
            for (i, entry) in entries.enumerated() {
                while state.paused && state.running { try? await Task.sleep(nanoseconds: 200_000_000) }
                if !state.running { break }
                currentMessage = "opening \(entry.normalized ?? entry.raw)"

                let result: CheckResult
                if !entry.valid {
                    result = ForegroundResult.build(entry: entry, installId: installID,
                                                    durationMs: 0, finalURL: nil,
                                                    outcome: "SKIPPED: invalid URL")
                } else {
                    let started = Date()
                    let outcome = await container.load(URL(string: entry.normalized!)!,
                                                      timeout: timeout)
                    let elapsed = Int(Date().timeIntervalSince(started) * 1000)
                    result = ForegroundResult.build(
                        entry: entry, installId: installID,
                        durationMs: elapsed,
                        finalURL: container.lastFinalURL,
                        outcome: outcome
                    )
                }
                logger.append(result)
                if result.pass { state.passCount += 1 } else { state.failCount += 1 }
                state.done = i + 1
                if i + 1 < entries.count {
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }
            }
            logger.close()
            state.running = false; state.paused = false
            currentMessage = nil
        }
    }

    private func stop() {
        state.running = false
        runTask?.cancel()
    }

    // MARK: - Background
    private var backgroundBody: some View {
        VStack(alignment: .leading, spacing: Theme.Space.lg) {
            Card(title: "OS budget — read this", systemImage: "info.circle") {
                MonoLabel(
                    "Background mode processes the queue in OS-granted slices " +
                    "(iOS BGTaskScheduler; Android WorkManager on the twin app). " +
                    "It is NOT instant or continuous — a long list will be swept over " +
                    "multiple OS-granted windows. Progress persists across kills.",
                    color: Theme.Color.faint
                )
            }
            Card(title: "session-bound background", systemImage: "clock.arrow.circlepath") {
                MonoLabel(
                    "This PoC runs the background probes inside the active session " +
                    "(no BGTaskScheduler identifier registered yet — see SUBMISSION.md). " +
                    "Foreground is the recommended mode for any list you actually want " +
                    "to see finish during a single sitting.",
                    color: Theme.Color.faint
                )
            }
            HStack {
                if !state.running {
                    PrimaryButton("Start (in-session)", systemImage: "bolt.horizontal.circle",
                                  enabled: state.prepared.contains(where: \.valid)) {
                        startBackgroundInSession()
                    }
                } else {
                    PrimaryButton("Stop", systemImage: "stop.fill") { stop() }
                }
                Spacer()
                PrimaryButton("See log", systemImage: "tablecells") { onSeeResults() }
            }
        }
    }

    // MARK: - Scheduled (usage-profile driven)
    @State private var lastProbability: Double?
    @State private var lastTickMinute: Int = 0
    @State private var profile: UsageProfile?

    private var scheduledBody: some View {
        VStack(alignment: .leading, spacing: Theme.Space.lg) {
            Card(title: "usage-profile schedule", systemImage: "chart.bar.xaxis") {
                MonoLabel(
                    "Every minute we look up p = profile[minute-of-day], apply " +
                    "the active mask, and roll Bernoulli(p). On heads, the next " +
                    "URL is opened via the WebView probe.",
                    color: Theme.Color.faint
                )
                if let profile, !profile.isEmpty {
                    MonoLabel("source: \(profile.sourceName ?? "—")", color: Theme.Color.ink)
                    MonoLabel("days: \(profile.totalDays) · records: \(profile.totalRecords)", color: Theme.Color.faint)
                    MonoLabel("peak: \(UsageProfile.hhmm(profile.peakMinute)) · p=\(String(format: "%.3f", profile.perMinute[profile.peakMinute]))",
                              color: Theme.Color.faint)
                } else {
                    StatusLabel("⚠ no profile loaded — import a TXT in Settings.", tone: .warn)
                }
                MonoLabel("mask: \(MaskMode(rawValue: maskRaw)?.label ?? "off")",
                          color: Theme.Color.faint)
                if let p = lastProbability {
                    StatusLabel(String(format: "last p(minute %d) = %.3f", lastTickMinute, p),
                                tone: p > 0 ? .accent : .info)
                }
            }
            if state.running || state.done > 0 {
                Card(title: "progress", systemImage: "play.fill") {
                    MonoLabel("\(state.done) of \(state.total) opened")
                    HStack {
                        StatusLabel("pass \(state.passCount)", tone: .accent)
                        StatusLabel("fail \(state.failCount)",
                                    tone: state.failCount > 0 ? .danger : .info)
                    }
                    if let currentMessage { MonoLabel(currentMessage, color: Theme.Color.faint) }
                }
            }
            // We reuse the same WebView from foreground mode for actual loads.
            Card(title: "webview", systemImage: "globe") {
                WebViewBox(container: webContainer)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.Radius.sm)
                            .stroke(Theme.Color.hairline)
                    )
            }
            HStack {
                if !state.running {
                    PrimaryButton("Start", systemImage: "play.fill",
                                  enabled: state.prepared.contains(where: \.valid)) {
                        startScheduled()
                    }
                } else {
                    PrimaryButton(state.paused ? "Resume" : "Pause",
                                  systemImage: state.paused ? "play.fill" : "pause.fill") {
                        state.paused.toggle()
                    }
                    PrimaryButton("Stop", systemImage: "stop.fill") { stop() }
                }
                Spacer()
                PrimaryButton("See log", systemImage: "tablecells") { onSeeResults() }
            }
        }
    }

    private func startScheduled() {
        let entries = state.prepared.filter(\.valid)
        guard !entries.isEmpty else { return }
        guard let profile, !profile.isEmpty else {
            currentMessage = "Import a usage-profile TXT in Settings first."
            return
        }
        let installID = state.installID
        let logger = CheckLogger(rotation: LogRotation(rawValue: rotationRaw) ?? .append)
        state.logger = logger
        state.total = entries.count; state.done = 0
        state.passCount = 0; state.failCount = 0
        state.running = true; state.paused = false
        currentMessage = nil
        lastProbability = nil; lastTickMinute = 0

        let timeout = timeoutSec
        let container = webContainer
        let snapshot = profile
        let mask = MaskMode(rawValue: maskRaw) ?? .none

        runTask = Task { @MainActor in
            var queue = entries
            while !queue.isEmpty {
                while state.paused && state.running { try? await Task.sleep(nanoseconds: 200_000_000) }
                if !state.running { break }

                let now = Date()
                let cal = Calendar.current
                let minuteOfDay = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
                let weekday = cal.component(.weekday, from: now)
                let p = snapshot.probability(forMinute: minuteOfDay, weekday: weekday, mask: mask)
                let roll = Double.random(in: 0..<1)
                let open = roll < p

                lastTickMinute = minuteOfDay
                lastProbability = p
                currentMessage = String(format: "minute %@  p=%.3f  %@",
                                        UsageProfile.hhmm(minuteOfDay), p,
                                        open ? "→ opening" : "skipped this minute")

                if open {
                    let entry = queue.removeFirst()
                    let started = Date()
                    let outcome = await container.load(URL(string: entry.normalized!)!,
                                                       timeout: timeout)
                    let elapsed = Int(Date().timeIntervalSince(started) * 1000)
                    let res = ForegroundResult.build(
                        entry: entry, installId: installID,
                        durationMs: elapsed,
                        finalURL: container.lastFinalURL,
                        outcome: outcome
                    )
                    logger.append(res)
                    if res.pass { state.passCount += 1 } else { state.failCount += 1 }
                    state.done = entries.count - queue.count
                }
                // Sleep until just after the next wall-clock minute boundary.
                let nextMinute = Calendar.current.nextDate(after: Date(),
                                                           matching: DateComponents(second: 1),
                                                           matchingPolicy: .nextTime) ?? Date().addingTimeInterval(60)
                let nano = UInt64(max(1, nextMinute.timeIntervalSince(Date()) * 1_000_000_000))
                try? await Task.sleep(nanoseconds: nano)
            }
            logger.close()
            state.running = false; state.paused = false
            currentMessage = nil
        }
    }

    private func startBackgroundInSession() {
        let entries = state.prepared
        let installID = state.installID
        let logger = CheckLogger(rotation: LogRotation(rawValue: rotationRaw) ?? .append)
        state.logger = logger
        state.total = entries.count
        state.done = 0; state.passCount = 0; state.failCount = 0
        state.running = true; state.paused = false

        let timeout = timeoutSec
        let delay = delaySec

        runTask = Task.detached(priority: .utility) {
            let checker = await BackgroundChecker(timeout: timeout, installId: installID)
            for (i, entry) in entries.enumerated() {
                if Task.isCancelled { break }
                let res = await checker.probe(entry)
                await MainActor.run {
                    logger.append(res)
                    if res.pass { state.passCount += 1 } else { state.failCount += 1 }
                    state.done = i + 1
                }
                if i + 1 < entries.count {
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }
            }
            await MainActor.run {
                logger.close()
                state.running = false
            }
        }
    }
}

// MARK: - WebView wrapper

@MainActor
final class WebContainer: ObservableObject {
    let webView: WKWebView
    private var continuation: CheckedContinuation<String, Never>?
    private var cancelTimer: Task<Void, Never>?
    private weak var navDelegate: NavDelegate?
    var lastFinalURL: String?

    init() {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        self.webView = WKWebView(frame: .zero, configuration: cfg)
        self.webView.customUserAgent = userAgent
        let d = NavDelegate { [weak self] outcome, finalURL in
            self?.lastFinalURL = finalURL
            self?.cancelTimer?.cancel(); self?.cancelTimer = nil
            self?.continuation?.resume(returning: outcome)
            self?.continuation = nil
        }
        self.navDelegate = d
        self.webView.navigationDelegate = d
    }

    /// Load `url` and resolve once onFinished or onError fires, or `timeout` lapses.
    func load(_ url: URL, timeout: TimeInterval) async -> String {
        lastFinalURL = nil
        return await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            self.continuation = cont
            cancelTimer = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                guard let self else { return }
                if let c = self.continuation {
                    self.continuation = nil
                    c.resume(returning: "TIMEOUT")
                    self.webView.stopLoading()
                }
            }
            webView.load(URLRequest(url: url))
        }
    }
}

private final class NavDelegate: NSObject, WKNavigationDelegate {
    let report: (String, String?) -> Void
    init(report: @escaping (String, String?) -> Void) { self.report = report }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        report("LOADED", webView.url?.absoluteString)
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        report("LOAD_ERROR: \(error.localizedDescription)", webView.url?.absoluteString)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        report("LOAD_ERROR: \(error.localizedDescription)", nil)
    }
}

struct WebViewBox: UIViewRepresentable {
    let container: WebContainer
    func makeUIView(context: Context) -> WKWebView { container.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
