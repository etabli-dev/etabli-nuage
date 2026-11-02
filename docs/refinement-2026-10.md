# October 2026 refinement

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

Unresolved external acceptance: Android/iOS device WebView, lifecycle, backup and
accessibility checks, and a real Nextcloud app-password
journey. Local HTTP/TLS fixtures establish transport behavior, not a production
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
