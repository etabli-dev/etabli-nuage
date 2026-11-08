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

The first exact-commit Windows workflow failed the delayed-upload regression's
three-second request wait, then stalled until the job's 30-minute deadline.
Linux and macOS packaging passed in that run. The regression now holds the PUT
response behind an explicit bounded gate until the newer run completes, and
releases/cancels it during cleanup. WebDAV request assertions are bounded;
per-test progress and a ten-minute test-step limit preserve useful failure
reports without consuming the entire packaging job.

The updated complete desktop suite passed locally again: 59 tests, no failures,
errors or skips (one Gradle worker). A final scoped repeat also passed after
adding an explicit assertion that the held PUT returns success rather than the
fallback timeout response. The original Windows failure is not treated
as resolved until the updated Windows CI run passes. Production code and the
previously verified DMG are unchanged by this test/CI follow-up.
