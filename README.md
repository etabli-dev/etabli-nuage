# Établi Nuage

> Check a CSV of links and keep exportable results, with optional Nextcloud storage.

`iOS` `Android` `Desktop preview` · Apache-2.0 · Part of the [Établi Suite](https://github.com/etabli-dev)

The implemented app is the link checker inherited from Établi Probe. Import a
local CSV or retrieve one by its Nextcloud WebDAV path, choose the URL column,
run sequential checks, then share the saved CSV or upload it to Nextcloud.
Contacts/CardDAV, calendar/CalDAV and a general file browser are future work;
they are not available in this version. Existing application IDs and saved data
paths retain the Probe name for compatibility. Not affiliated with Nextcloud GmbH.

## Availability

Établi Nuage is **under active development**. There are no App Store, Google Play or F-Droid releases yet.

- **Android:** a development **APK** (v0.1.0, June 2026, older source) is prepared as a draft on GitHub Releases but not published yet; build from source meanwhile (below).
- **Desktop (Ubuntu/Debian, Windows, macOS):** **development preview** in `desktop/`, built from source. No packaged release yet; only the Linux build has been run and checked locally.
- **App Store (iOS):** planned — not yet available.
- **Google Play:** planned — not yet available.
- **F-Droid:** planned — not yet available.

**Platforms:** desktop first, with iOS and Android as companions. What runs where,
how each platform is built and verified, and the next release steps are in the
[platform roadmap](docs/platform-roadmap.md).

## First use

1. Open **Source** and choose a CSV. Comma, semicolon and tab delimiters,
   quoted/multiline fields, UTF-8 BOM and Latin-1 fallback are supported. Confirm
   the header switch and select the URL column. The original file is never changed.
2. Choose a mode in **Settings**, then start from **Run**. Foreground renders each
   page; background checks HTTP status without retaining response bodies. Both
   require the run screen to remain active. Leaving it or stopping cancels the run.
3. Open **Results** to inspect this run and select a saved CSV to share/upload.
   Completed rows are flushed before they count as done. Saved files remain
   selectable after restart; automatic resumption of unfinished queues is not implemented.
4. Optional: enter your HTTPS Nextcloud base URL, username and app password in
   Settings, then **Save & test**. Fetch paths and upload directories are relative
   to your Nextcloud files. Changing the server or username requires a password
   entry; saved credentials are never silently forwarded to another server.

Example input:

```csv
url,label
https://example.org,Example
```

CSV input is limited to 10 MiB, 100,000 records, 1,024 columns and one million
cells. WebDAV listings are limited to 2 MiB. HTTP redirects are limited to ten
attempts within one timeout; authenticated WebDAV transfers do not follow redirects.
On iOS, log uploads are limited to 10 MiB; larger logs can still be shared as files.
Use per-run rotation for long-running collections.

Scheduled mode samples a minute-of-day activity profile while this screen stays
active. Imported open/close events produce an empirical activity fraction across
observed dates, including dates with no inferred activity. Every overlapped minute
is counted once per date. Unmatched opens imply one minute; unmatched closes have
no inferred activity. This is a sampling heuristic, not a prediction of future
phone use. Inputs over 100,000 events, intervals over 31 days or more than one
million expanded minutes are refused with an error.

## Build and verification

Android: JDK 17, Android SDK 36 and build-tools 35.0.0; set `ANDROID_HOME`.

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is for testing. The release AAB requires signing and device
acceptance before store submission. iOS: open `ios/EtabliProbe.xcodeproj` in
Xcode, select your development team and an iOS 17+ destination, then run.
Foundation core checks also work with macOS command-line tools and Python 3:

```sh
swiftc ios/App/Models.swift ios/App/IO/CsvLoader.swift \
  ios/App/Engine/Logger.swift ios/App/Engine/Checker.swift \
  ios/App/Engine/UsageProfile.swift ios/App/Net/WebDavClient.swift \
  scripts/check_core.swift -o /tmp/nuage-core-check
python3 scripts/check_http.py /tmp/nuage-core-check
```

Desktop preview (Linux, Windows, macOS): JDK 17 or newer to start Gradle. The
desktop app performs the HTTP status (background) check; page rendering and
scheduled runs remain mobile-only. Open a CSV with the file dialog, by drag and
drop or as the first command-line argument, then export the results CSV to a path
you choose.

```sh
cd desktop
./gradlew test        # engine, WebDAV and UI tests against loopback servers
./gradlew run         # start the app
./gradlew packageDeb  # or packageMsi / packageDmg on Windows / macOS
```

The tests use local HTTP/TLS fixtures, not a real Nextcloud account. See
[the refinement record](docs/refinement-2026-10.md) for observed results and
[the iOS submission checklist](ios/SUBMISSION.md) for outstanding device gates.

## Privacy

No analytics or author-operated service. HTTP probes contact the URLs you provide
and their redirects; rendered pages can load their own third-party resources.
HTTP links are supported without transport encryption. Nextcloud transfers require
HTTPS and normal certificate validation, with no TLS bypass.

Nextcloud credentials are kept in iOS Keychain or Android EncryptedSharedPreferences.
Android excludes the encrypted credential file from backup because its Keystore
key cannot be restored with it. Logs contain the checked URLs, outcomes, timestamps
and a per-install UUID. Logs and usage profiles are local, unencrypted files and
may be included in device backups. Upload/share happens only through your action.

The desktop preview asks for the Nextcloud app password once per session and keeps
it in memory only; it is never written to disk. Server URL, username, upload folder,
check settings, theme and the per-install UUID are stored in a plain settings file
in the per-user configuration folder. Results stay in memory until you export or
upload them.

## Layout and release scope

`ios/` contains SwiftUI/Foundation sources, `android/` Compose/OkHttp sources,
`desktop/` the Compose Multiplatform desktop preview, and `fastlane/` store
metadata. Device WebView lifecycle, accessibility, credential restore, actual
Nextcloud interoperability and signed-store acceptance remain separate release
gates. No unattended background service is implemented.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Copyright 2026 R. Heller.
