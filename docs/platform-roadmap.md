# Platform roadmap

Status as of 2026-11-08. This page plans Établi Nuage for Ubuntu/Debian, Windows,
macOS, iOS and Android. It separates what exists and was verified from what is
planned; "not verified locally (CI only)" means a build is configured but has not
run on that platform yet.

## 1. Platform role

Établi Nuage runs on **both desktop and mobile, desktop first**. Checking a CSV of
links is desk work: the lists come out of spreadsheets, reference managers and
exports, they can hold thousands of rows, and the results go back into a
spreadsheet. The desktop app is therefore the primary place to import a CSV, pick
the URL column, run the sequential checks, review the results table and export
the results CSV.

- **Ubuntu/Debian, Windows, macOS (desktop):** the main tool for importing,
  checking, reviewing and exporting larger lists; optional Nextcloud WebDAV fetch
  and upload with an app password.
- **iOS and Android (mobile):** the companion for smaller lists and for checks
  that only a phone can do: rendering each page in the system web view
  (foreground mode), usage-profile scheduled runs, sharing the saved CSV through
  the share sheet and uploading it to Nextcloud on the go.

## 2. Current state

| Platform | Status | Technology | How it is built | What is verified | Distribution |
|---|---|---|---|---|---|
| Ubuntu/Debian | Development preview | Kotlin 2.2.20, Compose Multiplatform 1.9.0 (Material 3) on JVM 17 with a bundled runtime, in [`desktop/`](https://github.com/etabli-dev/etabli-nuage/tree/main/desktop) | `cd desktop && ./gradlew packageDeb` (Compose Gradle plugin, jpackage) | Locally on Ubuntu 26.04 x86-64 (X11 under Xvfb), 2026-11-08: 56 unit and UI tests passed; the packaged app from the `.deb` payload opened a CSV through the GTK file dialog, by drag and drop and as an argument, ran checks against a loopback HTTP server, listed the results and exported them through the save dialog; `.deb` metadata and contents inspected, not installed system-wide; no real Nextcloud server contacted. The CI job is written but has not run yet | None. Once the workflow runs, an unsigned `.deb` is available as a CI artifact |
| Windows | Development preview (same code as Linux) | as Ubuntu/Debian | `./gradlew packageMsi` on Windows (the Compose plugin downloads WiX 3) | Not verified locally (CI only); the CI job has not run yet | None. Unsigned `.msi` CI artifact once the workflow runs |
| macOS | Development preview (same code as Linux) | as Ubuntu/Debian | `./gradlew packageDmg` on macOS | Not verified locally (CI only); the CI job has not run yet | None. Unsigned `.dmg` CI artifact (Apple silicon runner) once the workflow runs |
| iOS | In development | SwiftUI + Foundation, [`ios/EtabliProbe.xcodeproj`](https://github.com/etabli-dev/etabli-nuage/tree/main/ios) | Xcode or `xcodebuild` (iOS 17+) | CI on macOS 15: Swift core checks and the unsigned simulator build passed (first on 2026-10-31, again on 2026-11-02). No installed simulator or device journey is recorded | None. App Store planned |
| Android | In development | Kotlin 1.9.24, Jetpack Compose, OkHttp, AGP 8.13.2, [`android/`](https://github.com/etabli-dev/etabli-nuage/tree/main/android) | `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease` | CI: tests, lint, debug APK and unsigned release AAB passed (2026-10-31 and 2026-11-02). Emulator journeys on API 35 (2026-11-02) are recorded in the [refinement record](refinement-2026-10.md) | A v0.1.0 APK from June 2026 (older source) exists only as an unpublished draft GitHub release. Google Play and F-Droid planned |

What the desktop preview does today: open a CSV through the system file dialog,
drag and drop or a command-line argument; choose the URL column (with the mobile
header detection and column guess); run HTTP status checks one at a time with
progress and cancel; show a filterable results table; export the results CSV to
a chosen path; fetch a CSV from and upload results to Nextcloud over WebDAV. The
Nextcloud app password is asked once per session and kept in memory only.

What it does not do yet: render pages in a web view (mobile foreground mode),
scheduled runs, a durable per-run log that survives a crash, remembering the app
password in the OS keyring, signed installers.

## 3. Architecture

**Now.** Three code bases share behaviour, not code:

- `ios/` — Swift: `CsvLoader`, `UrlNormalize`, `BackgroundChecker`, `WebDavClient`,
  SwiftUI screens.
- `android/` — Kotlin: the same engine in `com.raban.etabli.probe.engine` and
  `.net`, Compose screens, EncryptedSharedPreferences for credentials.
- `desktop/` — Kotlin/JVM: a port of the Android engine. `CsvLoader.kt` is copied
  unchanged; `Checker.kt` differs only in the coroutine `resume` overload;
  `WebDavClient.kt` uses the JDK StAX parser instead of Android's XmlPullParser.
  Logic that lives in the mobile screens was extracted into plain functions
  (`UrlColumn.kt` for the column guess and row numbering, `SequentialRun.kt` for
  the run loop, `ResultsCsv.kt` for the results file). Colours, spacing, radii and
  type scale are generated at build time from
  [`design/coder-design-system.json`](https://github.com/etabli-dev/etabli-nuage/blob/main/design/coder-design-system.json).

All three produce the same results CSV columns (`row_index,url,mode,result,final_url,duration_ms,timestamp_local,timestamp_utc,install_id`)
and the same pass rule (HTTP 200–399, or `LOADED` for page rendering).

**Target.** A Kotlin Multiplatform module `shared/` holds the engine once:

- `commonMain`: CSV parsing, URL normalisation, column guess, results CSV, run
  loop, WebDAV request building and multistatus parsing (behind a small XML
  reader interface).
- `jvmMain` (desktop) and `androidMain`: OkHttp transport and the platform XML
  reader (StAX / XmlPullParser).
- `android/` and `desktop/` depend on `shared/`; the engine tests move there and
  run once for both. Shared Compose UI (Compose Multiplatform `commonMain`
  screens) is a later, separate step.
- iOS stays SwiftUI with its Swift core. Consuming `shared/` from Swift through a
  Kotlin/Native framework is possible but is an owner decision, not part of this
  plan.

Prerequisite: the Android app must move from Kotlin 1.9.24 to Kotlin 2.x with the
Compose compiler Gradle plugin, which needs a machine with the Android SDK and an
emulator or device to re-run the recorded journeys.

## 4. Per-platform execution

### Ubuntu/Debian

- **Build and run:** JDK 17 or newer to start Gradle (the build provisions a JDK 17
  toolchain if none is installed).
  `cd desktop && ./gradlew run` (development), `./gradlew test`,
  `./gradlew packageDeb` → `desktop/build/compose/binaries/main/deb/etabli-nuage_0.1.0_amd64.deb`
  (the package metadata says `Version: 0.1.0-1`; jpackage always adds the Debian
  revision, the build renames the file). Install with
  `sudo apt install ./etabli-nuage_0.1.0_amd64.deb`; it installs to
  `/opt/etabli-nuage` with a menu entry in *Network*.
- **Package formats:** `.deb` now. AppImage (from `createDistributable` plus
  `appimagetool`) and Flatpak/Flathub (needs a manifest with offline Gradle
  dependencies) only if users on other distributions ask; RPM is available from
  the same plugin (`packageRpm`) but not planned.
- **Signing prerequisites:** none for a `.deb` attached to a GitHub release
  (publish SHA-256 checksums). An APT repository would need a GPG signing key held
  by the owner.
- **CI job:** `.github/workflows/desktop.yml`, `ubuntu-24.04`: `test`, `packageDeb`,
  artifact `etabli-nuage-desktop-deb`.
- **Acceptance:** CI green; on clean Ubuntu 24.04 LTS, Ubuntu 26.04 LTS and
  Debian 13 machines the package installs and removes cleanly, the menu entry
  starts the app, file dialog, drag and drop from the file manager, run, cancel,
  export and a real Nextcloud app-password fetch and upload work; settings land in
  `~/.config/etabli-nuage/settings.properties` and contain no password.

### Windows

- **Build and run:** on Windows with JDK 17+: `cd desktop`, `.\gradlew.bat run`,
  `.\gradlew.bat packageMsi` → `desktop\build\compose\binaries\main\msi\`.
  The MSI is configured to install per user (no administrator rights) with a fixed
  upgrade code, so later versions upgrade in place; neither is verified yet.
- **Package formats:** MSI now; MSIX only if a Microsoft Store listing is wanted.
- **Signing prerequisites:** an Authenticode code-signing certificate (OV/EV
  certificate or Azure Trusted Signing) provided by the owner, stored as CI
  secrets (for example `WINDOWS_SIGNING_CERT_BASE64` and
  `WINDOWS_SIGNING_CERT_PASSWORD`), and a `signtool sign` step after
  `packageMsi`. Unsigned MSIs trigger SmartScreen warnings.
- **CI job:** `desktop.yml`, `windows-latest`: `test`, `packageMsi`, artifact
  `etabli-nuage-desktop-msi`.
- **Acceptance:** CI green; on Windows 11 the MSI installs without administrator
  rights, the Start menu entry starts the app, file dialog, drag and drop from
  Explorer, run, cancel, export and Nextcloud work, settings are in
  `%APPDATA%\Etabli Nuage`, an upgrade keeps them, uninstall removes the program;
  `signtool verify /pa` passes once signing exists.

### macOS

- **Build and run:** on macOS with JDK 17+: `cd desktop && ./gradlew run`,
  `./gradlew packageDmg` → `desktop/build/compose/binaries/main/dmg/`. The
  bundle identifier is `com.raban.etabli.probe` (the iOS bundle identifier). The
  bundle version is `1.0.0` because DMG bundles require a major version above zero
  (it matches the iOS marketing version 1.0); the preview is 0.1.0 elsewhere.
- **Package formats:** DMG with Developer ID signing and notarization. The Mac
  App Store is not planned.
- **Signing prerequisites:** Apple Developer Program membership, a *Developer ID
  Application* certificate exported as `.p12`, and notarization credentials (App
  Store Connect API key, or Apple ID with an app-specific password, plus the team
  ID), stored as CI secrets. The Compose plugin's `macOS { signing { … }
  notarization { … } }` settings and `notarizeDmg` task use them.
- **CI job:** `desktop.yml`, `macos-15` (Apple silicon): `test`, `packageDmg`,
  artifact `etabli-nuage-desktop-dmg`. An Intel build needs an additional Intel
  runner job if Intel Macs are to be supported.
- **Acceptance:** CI green; on macOS 15 the notarized DMG opens without Gatekeeper
  warnings (`spctl -a -vv` and `xcrun stapler validate` pass), file dialog, drag
  and drop from Finder, run, cancel, export and Nextcloud work, settings are in
  `~/Library/Application Support/Etabli Nuage`. Until signing exists, unsigned
  test builds are opened with Control-click → Open.

### iOS

- **Build and run:** open `ios/EtabliProbe.xcodeproj` in Xcode, choose a team and
  an iOS 17+ destination; CI builds the simulator target unsigned
  (`xcodebuild -project ios/EtabliProbe.xcodeproj -target EtabliProbe -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build`).
- **Package formats:** TestFlight, then App Store.
- **Signing prerequisites:** Apple Developer Program membership (shared with macOS
  notarization), distribution certificate and provisioning profile (or Xcode
  automatic signing), an App Store Connect API key for CI uploads, App Store
  listing, privacy details and export-compliance answers
  (see [`ios/SUBMISSION.md`](https://github.com/etabli-dev/etabli-nuage/blob/main/ios/SUBMISSION.md)).
- **CI job:** `android.yml` job `swift-core` (macOS 15): Swift core checks and the
  unsigned simulator build. A signed archive and TestFlight upload job is added
  once the signing secrets exist.
- **Acceptance:** the device checks listed in `ios/SUBMISSION.md` (web view
  stop/timeout, HTTP errors, saved-log sharing, Keychain reconnect, existing data,
  ATS behaviour for HTTP links), then TestFlight review and App Store review.

### Android

- **Build and run:** JDK 17, Android SDK 36, build-tools 35.0.0:
  `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease`,
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Package formats:** signed APK on GitHub Releases, AAB for Google Play, and
  F-Droid: the fastlane metadata in `fastlane/metadata/android` is already
  present, the app has no Google services; an F-Droid build recipe is still needed.
- **Signing prerequisites:** a real upload keystore and passwords (the local
  placeholder must be replaced, see
  [`android/SUBMISSION.md`](https://github.com/etabli-dev/etabli-nuage/blob/main/android/SUBMISSION.md)),
  stored as CI secrets for release builds; a Google Play developer account; Play
  listing, data-safety form and content rating.
- **CI job:** `android.yml` job `android` (Ubuntu 24.04): tests, lint, debug APK
  and unsigned release AAB.
- **Acceptance:** the device, backup, accessibility and live-Nextcloud gates in
  the [refinement record](refinement-2026-10.md), a signed APK that installs over
  the previous build, Play internal testing, F-Droid build passes.

## 5. Milestones

Ordered so they can be executed one after another; M1 and M2 need no new accounts.

1. **M1 — Desktop CI green.** Push `desktop/` and `desktop.yml`; all three jobs
   pass. *Exit:* `etabli-nuage-desktop-deb`, `-msi` and `-dmg` artifacts download,
   and the test reports show all tests passing on Linux, Windows and macOS.
2. **M2 — Desktop acceptance on real machines.** Install the M1 artifacts on
   Ubuntu 24.04, Ubuntu 26.04, Debian 13, Windows 11 and macOS 15 and walk the
   acceptance lists in section 4, including a real Nextcloud app-password fetch and
   upload. *Exit:* results recorded in `docs/` with dates and versions; defects
   filed and fixed.
3. **M3 — Durable desktop log.** Port the mobile append-only log: each completed
   row is flushed to a per-run file in the per-user data folder before it counts,
   and saved runs are listed after a restart. *Exit:* killing the app during a
   run leaves a valid CSV with every completed row; tests cover it.
4. **M4 — App password in the OS keyring (optional "remember").** After the owner
   picks a maintained library or approach, store the Nextcloud app password in the
   Secret Service (Linux), Credential Manager (Windows) or Keychain (macOS), never
   in a file; keep "ask each session" as the default. *Exit:* manual check on all
   three systems; no password in settings files or logs.
5. **M5 — `shared/` Kotlin Multiplatform module.** Upgrade Android to Kotlin 2.x,
   move the engine, WebDAV client and results CSV into `shared/`, make `android/`
   and `desktop/` depend on it and delete the copies. *Exit:* Android and desktop
   CI green, engine tests run once in `shared/`, the Android emulator journeys from
   2026-11-02 re-run and pass.
6. **M6 — Signed desktop releases.** Add Windows Authenticode signing, macOS
   Developer ID signing and notarization, SHA-256 checksums, and a tag-triggered
   release workflow that attaches the `.deb`, `.msi` and `.dmg` to a GitHub
   release. *Exit:* `signtool verify`, `spctl`/`stapler` and checksum verification
   pass on downloaded release files; the README availability section links them.
7. **M7 — Android release.** Real upload key, signed APK on GitHub Releases, AAB in
   Play internal testing, F-Droid recipe. *Exit:* the gates in
   `android/SUBMISSION.md` are checked and the release is public.
8. **M8 — iOS release.** Signed archive, TestFlight, device acceptance, App Store
   submission. *Exit:* the gates in `ios/SUBMISSION.md` are checked and the build
   is approved.
9. **M9 — Further channels (only on demand).** Flatpak/Flathub or AppImage for
   other Linux distributions; MSIX/Microsoft Store; Intel macOS build.

## 6. Owner decisions and prerequisites

- **Draft release v0.1.0:** a June 2026 Android APK built from older source sits in
  an unpublished draft release. Decide whether to publish a fresh build, or delete
  the draft.
- **Desktop credentials:** keep "ask once per session" (current), or approve an
  OS keyring integration (M4) and the library to use.
- **Accounts and certificates:** Apple Developer Program (iOS and macOS
  notarization), Windows code-signing certificate or Azure Trusted Signing, Google
  Play developer account, Android upload key; F-Droid submission yes or no.
- **Distribution channels:** `.deb` only on Linux, or also Flatpak/AppImage; APT
  repository or GitHub Releases only; Intel Macs supported or Apple silicon only.
- **Versioning:** desktop starts at 0.1.0 (macOS bundle 1.0.0, see above) while the
  mobile apps say 1.0; decide whether all platforms move to a common version line.
- **Identifiers:** the desktop app reuses `com.raban.etabli.probe` as its macOS
  bundle identifier, and installers use the ASCII name "Etabli Nuage" (the window
  title is "Établi Nuage"); confirm both.
- **Typography:** bundle JetBrains Mono (SIL OFL) so the desktop matches the design
  file exactly, or keep the platform monospace font.
- **Scope:** Contacts (CardDAV), Calendar (CalDAV) and a general file browser from
  [`ARCHITECTURE.md`](https://github.com/etabli-dev/etabli-nuage/blob/main/ARCHITECTURE.md)
  remain future work on every platform.
