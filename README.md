# Établi Nuage

> Your Nextcloud — files, contacts, calendar — in one app.

`iOS` `Android` · Apache-2.0 · Part of the [Établi Suite](https://github.com/etabli-dev)

> **Note:** expanded from EtabliProbe (was a WebDAV link checker; now the link-checker is one module of a full Nextcloud client).

Établi Nuage is a unified client for your self-hosted Nextcloud server. One account, four synced modules: Files (WebDAV browse/upload/download), Contacts (CardDAV), Calendar (CalDAV), and a Link Checker that probes a CSV of URLs and syncs result logs back to your server. Talks only to your own Nextcloud instance; credentials live in the platform secure store. (Not affiliated with Nextcloud GmbH.)

## Modules

One Nextcloud account, four synced modules:

- **Files (WebDAV)**
- **Contacts (CardDAV)**
- **Calendar (CalDAV)**
- **Link Checker (CSV + WebDAV log sync)**

Each module is independently navigable but shares a single authenticated session and the platform secure store for credentials.

## Availability

Établi Nuage is **under active development** (Contacts/CardDAV and Calendar/CalDAV are still being finished). There are no App Store, Google Play or F-Droid releases yet.

- **Android:** install the current **development build** as a signed **APK** from **[GitHub Releases](../../releases)**.
- **App Store (iOS):** planned — not yet available.
- **Google Play:** planned — not yet available.
- **F-Droid:** planned — not yet available.

## Privacy

No analytics. No third-party SDKs. You sign in with **your own Nextcloud account**; that credential is stored only in the platform secure store (iOS Keychain / Android EncryptedSharedPreferences) and is sent only to your server.

## Repository layout

```
ios/        SwiftUI app
android/    Kotlin + Jetpack Compose app
fastlane/   F-Droid / store listing metadata
```

Both platforms are one product, sharing the Coder Design System tokens.

## Tech

iOS: SwiftUI + URLSession (WebDAV/CalDAV/CardDAV), Keychain. Android: Compose, OkHttp, ical4j-style CalDAV + vCard parsing, EncryptedSharedPreferences (Tink)

**Status:** Files + Link Checker carried over from EtabliProbe; Contacts/Calendar modules in development

## Support development

- 💚 **[Liberapay](https://liberapay.com/rabanheller/)** — recurring, 0% commission, to be shown on the F-Droid listing once published.
- ☕ [Buy Me a Coffee](https://buymeacoffee.com/rabanheller) — one-off tip (also the in-app link on iOS/Android).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 R. Heller.
