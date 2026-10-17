# Chatto Mobile

Experimental Capacitor clients that embed the shared static frontend without a
Chatto backend.

- **iOS** — system authentication session, TestFlight / local Xcode builds.
- **Android** — foreground realtime notification service (no FCM), WebView OAuth
  with navigation capture, sideload APK builds.

## iOS

Install Xcode with an iOS Simulator runtime. From the repository root:

```sh
mise mobile-ios-build
mise mobile-ios-open
```

`mobile-ios-build` builds the frontend, copies its SPA shell into the iOS
project, and builds an unsigned Simulator app under
`.context/mobile-ios-build/Build/Products/Debug-iphonesimulator/Chatto.app`.
`mobile-ios-open` refreshes the bundle and opens Xcode. Run
`mise mobile-ios-sync` after frontend changes.

Sign-in uses the built-in client `eu.chattocorp.chatto.mobile` with callback
`eu.chattocorp.chatto.mobile:/oauth/callback`. The server must include that
mobile OAuth registration.

## Android

Requires JDK 21+, Android SDK, and a device or emulator. From the repository
root:

```sh
mise mobile-android-sync
mise mobile-android-apk
# or: mise mobile-android-run
```

`mobile-android-sync` builds the frontend, bundles `src/glue.ts` into
`www/capacitor-glue.js`, and runs `cap sync android`. The foreground service
opens `/api/realtime` (protocol v4) for the active signed-in server, hydrates
created notification IDs through ConnectRPC, and posts OS banners while the UI
is in the background. There is no FCM or Play Services push relay.

Android sign-in uses the Capacitor HTTPS origin (`https://localhost`) with
CIMD and WebView navigation capture. Prefer an HTTPS Chatto server the device
can reach.

## Connect a server

Use an HTTPS Chatto server. Existing releases without the mobile OAuth
registration cannot complete iOS native sign-in. The app does not bypass
certificate validation.

### Local development

Start `mise dev` in a separate terminal and use the HTTPS server URL that it
prints. After frontend changes, re-run the platform sync task and relaunch the
app. The Simulator or emulator must trust the local CA; a physical device needs
a reachable server address (`localhost` on the device is the device itself).

## Current limits

- No App Store / Play Store release automation.
- Android notifications are a foreground service, not system push.
- No offline message store or native incoming-call ownership.
- iOS still has no native notification service in this tree.

See [FDR-048](../../docs/fdr/FDR-048-chatto-mobile.md) and
[ADR-099](../../docs/adr/ADR-099-capacitor-mobile-client.md).
