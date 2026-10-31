# Établi Nuage — iOS submission checklist

## Build outputs
- Historical simulator screenshots are retained under `screenshots/`; they do not verify this refinement.
- On 2026-10-30, Foundation models/CSV/probes/DAV paths/logging/profile checks compile and pass locally. Xcode is absent: the current SwiftUI app, simulator and device build remain unverified.
- Pure-Swift sources under `App/` (Xcode 16's File-System-Synchronized group, so files are auto-picked up — no `.pbxproj` surgery needed when adding new files).

## What's done
- Native SwiftUI shell with the byte-identical Coder DS `Theme.swift` shared across the Etabli iOS suite
- TabView with Source / Run / Results / Settings
- CSV source picker via `UIDocumentPicker` (`.commaSeparatedText`)
- WebDAV client via URLSession only (PROPFIND / GET / PUT / MKCOL, HTTP Basic with the Nextcloud app password) — **no TLS bypass anywhere**
- WKWebView-driven foreground checker with onPageFinished / onError / timeout completer
- URLSession-driven background checker (`BackgroundChecker` actor) — `ephemeral` config, configurable per-request delay + timeout
- Append-only CSV logger in `Documents/probe_logs/`
- Keychain-backed credentials store + per-install UUID (`Keychain.swift`)
- Pause / resume / stop in foreground
- Pass / fail filter on Results, total card, Share-CSV via `UIActivityViewController`
- Upload-to-Nextcloud via `WebDavClient.put(directory:, file:, data:, contentType:)`
- Settings: mode chips, delay/timeout sliders, Nextcloud creds with "Save & test" probing PROPFIND, theme chips, install ID display

## What you must do before publishing
- [ ] Sign with your Apple Developer team in Xcode (target → Signing & Capabilities)
- [ ] Build/run the current app on simulator and a device; verify stop/timeout, HTTP errors, saved-log sharing, Keychain reconnect and existing data
- [ ] Verify ATS behavior for user-entered HTTP links; document the arbitrary-load exception for this link-checker use case in submission notes (DAV still enforces HTTPS)
- [ ] Privacy policy URL
- [ ] App Store Connect listing assets: 1024×1024 icon, screenshots, description
- [ ] App Privacy form — declare: no data collection; credentials stored on-device; checks contact supplied URLs and page resources; WebDAV traffic goes to the user's Nextcloud
- [ ] Encryption export declaration — `ITSAppUsesNonExemptEncryption=NO` if you only use Apple system crypto
- [ ] **Background-mode caveat**: This PoC's "background" tab runs the same URLSession loop in the foreground (no `BGTaskScheduler` identifier registered). For true off-screen sweeps you'd register a `BGProcessingTaskRequest`, add the `processing` mode to `UIBackgroundModes` in Info.plist, and submit the task before backgrounding. Out of scope here.

## How to run
```bash
cd ios
open EtabliProbe.xcodeproj
# Build for simulator:
xcodebuild -project EtabliProbe.xcodeproj -scheme EtabliProbe \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -sdk iphonesimulator build CODE_SIGNING_ALLOWED=NO
```

## File layout
```
App/
  EtabliProbeApp.swift     entry, @AppStorage theme wiring
  AppState.swift           @Observable shared state
  Models.swift             value types
  Theme.swift              byte-identical Coder DS
  Net/
    Keychain.swift         Security framework wrapper
    WebDavClient.swift     URLSession PROPFIND/GET/PUT/MKCOL + XMLParser
  IO/
    CsvLoader.swift        RFC-4180-ish, comma/semicolon/tab auto-detect
  Engine/
    Logger.swift           append-only CSV
    Checker.swift          BackgroundChecker actor + ForegroundResult builder
    QueueStore.swift       JSON queue for future BGTaskScheduler wiring
  Shell/
    RootView.swift         TabView
    SourceView.swift       picker + DAV fetch + column map
    RunView.swift          WKWebView foreground + in-session background
    ResultsView.swift      log table + share + upload
    SettingsView.swift     mode/delay/timeout/Nextcloud/theme
  PrivacyInfo.xcprivacy    (carried over from Focus, edit before submission)
  Assets.xcassets          icon + launch assets
```

## Why CSV not xlsx
The other Etabli iOS apps are dep-free (no SwiftPM packages). Reading xlsx
natively requires either rolling a minimal ZIP + XML reader (≈ 300 LOC and
fiddly) or adding a SwiftPM dep. To stay consistent with the rest of the
suite, this PoC accepts CSV only. Most spreadsheet apps export to CSV in
two clicks, and the rest of the app (URL list → check → log) is identical.
