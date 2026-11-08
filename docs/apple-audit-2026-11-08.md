# Nuage: Apple build and recovery audit, 2026-11-08

The desktop runner now creates a separate CSV for every run and flushes each completed row before advancing its displayed counters. Cancelling a run cannot interrupt the write of a completed result. Previous run files are listed in Results after restart; the files can be opened independently of the app. This implements the durable-log portion of M3. A real process-kill acceptance exercise remains to be performed.

Export and upload completion are tied to the run that supplied their data. Finishing an older upload no longer marks a newer run as saved. Regression coverage includes CSV persistence, cancellation and a delayed Nextcloud upload overlapping a new run.

## Native iOS validation

The existing Swift validation scripts passed, and the full Xcode target built for the generic iOS Simulator with Xcode 27.2 and `CODE_SIGNING_ALLOWED=NO`. This includes the SwiftUI application, not only the standalone model checks. No live account was used. Simulator interaction, real-device acceptance, signing, notarization and store distribution are separate remaining checks.

## Desktop validation

On the final source, `cd desktop && ./gradlew --no-daemon --max-workers=2 test packageDmg` passed: 59 tests, zero failures/errors/skips, and a macOS DMG built with the bundled Java runtime. This repeated the initial successful test/package round after the final cancellation and restart-list refinements.

Artifact: `desktop/build/compose/binaries/main/dmg/Etabli Nuage-1.0.0.dmg` (69,389,972 bytes). SHA-256: `fe66721aa9536e126505afb71cf83a988f00bd8e64699987fce455f913dd5534`.

The package is not Developer ID signed or notarized. The test suite covers desktop UI and state behavior, but no new installed macOS interactive or live Nextcloud acceptance is claimed.

## Windows CI follow-up, 2026-11-09

The first exact-commit Windows workflow failed a request wait, then stalled on
an unbounded request assertion until the job deadline. Bounded request checks,
per-test progress, and a deterministic delayed-PUT gate exposed the actual cause
in the next run: Windows MockWebServer URLs used `127.0.0.1`, while the test TLS
certificate covered only `localhost`. This was a fixture hostname mismatch,
not evidence that production hostname verification should be weakened.

All loopback HTTPS fixtures now cover localhost, IPv4 loopback and IPv6 loopback.
A dedicated literal-IPv4 request regression preserves normal hostname validation.
The delayed-upload test still holds a successful PUT response behind an explicit
bounded gate until the newer run completes, and releases/cancels it on cleanup.
Per-test logs and a ten-minute test-step limit keep future failures diagnosable.

The earlier bounded-gate changes passed 59 local tests and a final scoped repeat.
The complete certificate-refined suite passed locally: 60 tests, no failures,
errors or skips, including the explicit literal-IPv4 regression. Windows CI
must still confirm the platform correction. Production code and the previously verified DMG are unchanged.
