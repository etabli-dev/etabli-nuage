# October–November 2026 refinement

## First published CI run and follow-up

On 2026-10-31, the [first published verification run](https://github.com/etabli-dev/etabli-nuage/actions/runs/41200116078) failed in the Android SDK installation step with `sdkmanager: command not found` (exit 127), before Android tests, lint or packaging ran. The workflow now explicitly installs Android command-line tools using `android-actions/setup-android` v4.0.4, pinned to its verified commit, before requesting API 36 and build-tools 35.0.0. Existing checks and artifact collection remain intact. YAML and the action reference were checked locally before the follow-up run. Earlier local build and device evidence below remains separate from this failed remote run.

Later on 2026-10-31, the [follow-up verification run](https://github.com/etabli-dev/etabli-nuage/actions/runs/41200535657) passed for [commit `0a662ac582c7`](https://github.com/etabli-dev/etabli-nuage/commit/0a662ac582c7e05d430a807ed3628fe546cb0f1b): Android `testDebugUnitTest lintDebug assembleDebug bundleRelease` and the full unsigned iOS simulator build succeeded. The [documentation build and deployment](https://github.com/etabli-dev/etabli-nuage/actions/runs/41200535715) also passed for that commit. These results verify compilation and the recorded automated checks; installed application journeys, release signing and store acceptance remain separate gates.

## Implemented product and plan

The current application is the CSV link checker inherited from Probe, with
WebDAV CSV retrieval and log upload. Contacts, calendar and a general file browser
are not implemented. Correct the advertised scope rather than claiming those
planned modules work.

Confirmed release blockers: background checking drains entire response bodies
into memory; cancellation is swallowed; redirect chains receive a fresh timeout
per hop. CSV parsing guesses headers from text and can drop a headerless first
record, counts separators inside quoted fields, and fails to strip a UTF-8 BOM.
WebDAV path construction does not encode usernames/segments, downloads are
unbounded, and automatic redirects are enabled on authenticated transfers.
Credential preferences are not excluded from backup despite Keystore dependence.
Android has no regression tests for these paths.

Sequence: bound/cancel status probes and transfers; validate DAV origins and paths
without altering source files; make CSV header interpretation explicit and preserve
quoted records; make append-only logs durable before reporting success; correct
scope, add unit/loopback failure tests and run Android checks. Then inspect the
corresponding Swift paths and keep device/live-Nextcloud gates explicit; the
subsequent unsigned iOS compilation result is recorded above.


## Android submission compatibility milestone

The baseline targeted API 34, below the [current Google Play submission requirement](https://developer.android.com/google/play/requirements/target-sdk). Compile/target SDK now move to 36 while retaining minimum SDK 26. AGP 8.13.2, Gradle 8.13 (distribution SHA-256 pinned), and JDK 17 are a [documented compatible toolchain](https://developer.android.com/build/releases/agp-8-13-0-release-notes). The API 27 navigation-bar attribute is now in qualified resources; Android 8.0 uses a dark navigation bar so its white icons remain visible. Validate tests, lint, debug APK and unsigned release packaging after migration. Device checks must cover insets, predictive back, rotation and existing saved data.

### DAV transport repair

Android's [HttpURLConnection implementation](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java) refuses PROPFIND and MKCOL. That makes the baseline connection test and directory creation unusable before reaching the server. Replace the DAV transport with OkHttp 4.12 (compatible with the retained Kotlin 1.9 toolchain), support custom methods, cancellation, streamed log upload and bounded downloads. Verify against a TLS loopback DAV server with normal certificate validation; do not introduce certificate bypasses in production.

## Completed core repairs

- CSV parsing preserves source rows under an explicit header switch, ignores quoted
  delimiters, supports BOM/CRLF/multiline fields, rejects malformed quoting and
  bounds bytes, records, columns and cells. URL normalisation rejects embedded
  credentials and unsupported schemes while preserving escaped paths.
- Android status probes and DAV requests use cancellable OkHttp calls. Redirect
  attempts share one deadline. DAV validates HTTPS paths, refuses automatic
  redirects, streams Android log uploads and rejects oversized responses/DTDs.
  TLS fixtures check custom PROPFIND/MKCOL methods, authentication, encoded paths,
  conflicts, redirects and hostile listings without bypassing trust validation.
- Swift uses bounded URLSession delegates with explicit cancellation and no
  authenticated redirects. The former weak WKNavigationDelegate is retained;
  navigation completions are tied to the active navigation, HTTP errors fail the
  check, and cancellation/timers cannot complete a later request.
- Both loggers report write failures and flush successful rows before updating
  memory. Per-run names are unique. Results exposes saved files after restart.
  iOS credential updates use one Keychain item, with legacy reads retained;
  Keychain updates preserve the old value if a write fails. Android excludes
  encrypted credentials from backup and preserves unreadable originals.
- Usage profiles count every overlapping minute and include observed inactive
  dates in the denominator. Impossible dates, pathological intervals and excessive
  expansion are rejected. Profile writes are atomic and failures are visible.
- README, store text and iOS checklist describe the actual link-checker scope,
  active-screen execution, input bounds, data flows and unresolved device gates.

## Validation and remaining acceptance

Android `testDebugUnitTest lintDebug assembleDebug bundleRelease` passed with
16 behavioural tests (`nuage-core-final`), including active-call cancellation. Swift Foundation compilation and loopback checks passed,
including exact CSV round trips, storage failure, profile boundaries, DAV path
encoding, redirect status, bounded responses and cancellation. Swift syntax
parsing passed for all application sources; that is not a SwiftUI typecheck.

The native macOS networking stack delayed the response callback until body bytes
arrived even when headers had already been sent. The fixture retains that stalled
case and verifies timeout; a second streamed case sends an initial chunk, delays
the remaining body, and verifies the checker returns before the complete body.
No claim of universally immediate header delivery is made. Apple's
[response delegate contract](https://developer.apple.com/documentation/foundation/urlsessiondatadelegate/urlsession(_:datatask:didreceive:completionhandler:))
is used to cancel once the platform exposes the response.

Unresolved external acceptance: broader Android lifecycle, backup and accessibility checks, installed iOS WebView behavior, and a real Nextcloud app-password journey. The scoped November 2 Android foreground CSV/WebView results are recorded below. Local HTTP/TLS fixtures establish transport behavior, not a production
server connection. Next release action: complete those device/account checks,
configure signing and prepare store metadata for approval. The iOS display name is corrected from Focus; an explicit ATS setting permits
user-entered HTTP targets while DAV remains HTTPS-only. The project compiled with
these settings in the successful unsigned iOS CI build; device behavior remains
unverified. General file browsing,
Contacts/Calendar and unattended queue scheduling stay outside this release.

The 2026-10-31 follow-up CI run above passed the full unsigned iOS simulator build and Swift core checks. A local Xcode application is unavailable; no installed iOS runtime result is claimed.

## Local credential lifecycle review

Confirmed: Disconnect could clear Nextcloud credentials while Save & test waited for DAV, then a late successful response wrote the credentials again. Connection attempts now carry a generation, and credential writes and removal share a lock. Disconnect invalidates pending writes and cancels the active UI connection job. Regression coverage starts with an existing account, releases a delayed result after removal, and verifies the account stays absent while the installation ID is retained; another case rejects an older attempt after a newer one succeeds.

Final native build evidence (2026-10-30, inspected 2026-10-31): `testDebugUnitTest lintDebug assembleDebug bundleRelease` passed in `nuage-android-device-prep`. The JUnit XML records 18 tests, zero failures, errors or skips. Both credential lifecycle regressions pass, including preservation of the installation ID after Disconnect. Debug APK and unsigned release AAB include these changes. Installed user journeys and signing/store acceptance remain separate gates.

## Installed Android CSV journeys — 2026-11-02

The installed debug APK SHA-256 was
`2bd446269b51c7068ea843e0389ded9c878ef853336f1f1d502a821229d6c2d4`.
On the API 35 ARM64 emulator with 4 KiB pages, an Android file-picker import
preserved explicit header/URL-column mapping and a quoted comma in the synthetic
CSV. With guest external networking disabled, the first two-row foreground run
recorded Chromium's `LOAD_ERROR: net::ERR_UNSAFE_PORT` for loopback port 1 and
`SKIPPED: invalid URL` for an unsupported ftp URL. This was not a TCP refusal test.
The Android share chooser displayed the saved `probe.csv`; it was cancelled
without selecting a recipient. The saved CSV remained available and byte-identical
after force-stop/relaunch.

A second three-row CSV used an owned HTTP server bound to host `127.0.0.1` through
one temporary `adb reverse` route. The actual foreground WebView run completed
3/3 with one pass and two failures: `LOADED` for HTTP 200, `HTTP_ERROR: 404` for
HTTP 404, and `SKIPPED: invalid URL`. Default append logging retained the original
two rows as an exact byte prefix and added the three correctly indexed rows.
The resulting five-row CSV and both source CSVs were preserved; hashes confirmed
input integrity and unchanged saved output after restart. Current-run totals
reset on process restart while the saved CSV remained selectable/shareable, as
documented. Delay 1000 ms, timeout 15 s and append mode were unchanged.

The owned reverse route was removed, the owned server stopped and guest network
settings restored. Screenshots, hierarchies, server request logs, CSV contents,
hashes and receipts are retained in the suite workspace
`.refinement/2026-10-30/android-local/games-flutter-20261102-pass1/`
(`nuage-result.json`, `nuage-positive-result.json`, `RESULTS.md`). These checks
establish the scoped installed Android foreground CSV/HTTP/log-persistence paths.
They do not verify a production server or Nextcloud account, background queues,
backup/restore, broad lifecycle/accessibility, physical-device, 16 KiB or installed
iOS behavior. Private signing and store acceptance remain open.

The earlier account-free pass on the same APK also verified Source/Run/Results empty states, picker cancellation, disabled unauthenticated WebDAV fetch, missing-field/HTTPS/username validation, masked passwords, a refused local HTTPS connection and relaunch without retaining failed credentials. Its separate suite receipt is `android-local/retry-20261102/native-journey-results.json`; neither pass contacted an external Nextcloud account.

## iOS appearance and production review — 2026-11-23

**Appearance (owner requirement).** Settings → Appearance offers System/Light/Dark (`appearance.mode`) and seven accent colours (`appearance.accent`, default Coder blue), with the suite-wide keys, IDs, values and German/English labels. The app's own surfaces use the Hugo Coder neutrals; the previous green accent is no longer the default and remains reachable as the Green swatch. Mode and accent apply at once to every window, sheet and alert. A mode saved under the earlier `coder.theme.preference` key moves to the new key once; unknown stored values fall back to System and Coder blue. Semantic colours (errors, warnings, success) do not follow the accent. `scripts/check_appearance.swift` checks the stored-value fallback, the colour table, WCAG contrast and the migration; Theme.swift, Appearance.swift and AppearanceModel.swift are identical in the six native apps.

**App language (owner requirement, 2026-11-24).** Settings → Appearance also offers the app language: System, Deutsch or English (`app.language`; unknown values fall back to System). The whole iOS interface (labels, buttons, chips, errors, empty states, accessibility labels and the local-network permission text) now exists in German and English in `Localizable.xcstrings` and `InfoPlist.xcstrings`, generated from the compiler-extracted keys. SwiftUI texts switch at once through the root locale; the choice is also stored as the app's preferred language, so system dialogs and messages created outside views follow from the next launch. Result codes written to the CSV log (for example `LOADED`, `TIMEOUT`) stay unchanged so logs remain comparable.

**Production review.** WebKit's navigation-policy callbacks are implemented in their async form: the closure forms with plain handler types no longer satisfy WebKit's main-actor requirements in the Swift 6 language mode and would silently stop being called. Network and parse messages no longer end in a doubled period. Pass counts and success messages use the semantic success colour; filter chips expose their selected state; screens keep a readable width on iPad.

**Verification of the current source revision (2026-11-24).** The appearance-model checks pass. The final German/English narrow-phone and tablet tours, signed Release installs and device acceptance for this revision remain pending. Earlier build or installation results do not establish acceptance of the current source.

Open: arbitrary loads stay enabled because the checker must open plain-HTTP links (WebDAV credentials remain HTTPS-only), which needs an App Review justification; Keychain items use after-first-unlock without the this-device-only restriction.
