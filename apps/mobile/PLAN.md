# Chatto Mobile App — Capacitor

## Goal

Bundle the existing SvelteKit static frontend into a Capacitor native shell for
Android and iOS. The most important feature is receiving chat message
notifications while the app is not active (backgrounded or killed).

**Track:** Built against the current Chatto frontend/server on this branch.
Remote sign-in uses the official mobile OAuth client via Custom Tabs
(`ChattoAuthorization`), not loopback WebView OAuth.

## Architecture

The frontend already uses `@sveltejs/adapter-static` with SPA fallback
(`200.html`). Capacitor serves this static build from disk inside a native
WebView. The mobile app is a thin shell — all application logic stays in the
existing frontend code.

```
apps/frontend/          apps/mobile/
  pnpm build              capacitor.config.ts
       |                  src/
       v                    glue.ts
  build/ (static SPA)      push.ts
       |                    media-picker.ts
       v                  android/  (committed)
  npx cap sync            ios/      (committed when added)
```

The mobile app connects to a remote Chatto server. The frontend already supports
multi-server connections via URL-based server selection, so no architectural
changes are needed for that.

## Current focus

Android first. iOS platform files will be added later and committed the same
way; do not block Android work on iOS tooling.

Native projects (`android/`, later `ios/`) are committed for reproducible
builds. Ignore only local IDE/build outputs inside those trees.

## Stages

### Stage 1 — Scaffold Capacitor Project

Create the minimal Capacitor project structure under `apps/mobile/`.

Files:

- `package.json` — workspace package `chatto-mobile` with `@capacitor/core`,
  `@capacitor/cli`, `@capacitor/android` (and `@capacitor/ios` when iOS starts).
- `capacitor.config.ts` — `appId: "eu.chattocorp.chatto.mobile"`, `appName: "Chatto"`,
  `webDir: "www"` (staged from the frontend build by `prepare-web`).
- `tsconfig.json` — minimal config for glue TypeScript files.
- `.gitignore` — ignore `node_modules/`, `www/`, and local Android/iOS build
  artefacts; keep `android/` / `ios/` tracked.
- `README.md` — setup and build instructions.
- `scripts/prepare-web.mjs` — stage `www/`, inject glue, create `index.html`.

Build workflow:

```sh
pnpm run build:frontend
cd apps/mobile
pnpm exec cap add android   # once
pnpm run sync
pnpm exec cap open android
```

`sync` copies the frontend build into `www/`, adds `index.html` from
`200.html`, bundles glue, then runs `cap sync`. The frontend/server keep
using `200.html` unchanged.

Dev workflow with live reload in emulator:

```sh
# In capacitor.config.ts, temporarily set:
#   server: { url: "http://10.0.2.2:5173", cleartext: true }
# Then run the frontend dev server and sync.
```

### Stage 2 — Glue Code

Create `apps/mobile/src/glue.ts` — the bridge between Capacitor platform APIs
and the web app. Loaded at app startup.

Responsibilities:

- **Platform detection** — expose `window.__CHATTO_CAPACITOR = true` so the
  frontend can detect the native shell without importing Capacitor directly.
- **Status bar** — configure appearance via `@capacitor/status-bar`.
- **Keyboard** — handle keyboard show/hide and viewport resize via
  `@capacitor/keyboard`.
- **Safe areas** — set CSS custom properties for notch/island insets.
- **Server URL** — read the configured Chatto server URL from device storage
  (Capacitor Preferences) and inject it so the frontend connects to the right
  host. If no URL is stored, the existing frontend server-add flow handles it.

No changes to files outside `apps/mobile/`.

### Stage 3 — Background notifications (Android foreground service)

**Decision:** No Google Play Services / FCM, no ntfy, no UnifiedPush, no extra
push infrastructure. The Chatto Android app itself keeps a connection to the
user's Chatto server and posts system notifications when the UI is not
active.

**Status:** Implemented (realtime WebSocket, protocol v4).

- Native `ChattoNotificationService` foreground service opens
  `/api/realtime` (v4 subscribe, live-only; hydrates creates via ConnectRPC).
- Alerts on non-silent notification creates only when the WebView UI is inactive.
- Capacitor plugin `ChattoNotifications` + JS
  `window.__CHATTO_NOTIFICATIONS_START/STOP/SET_UI_ACTIVE`.
- Tap opens SPA path `/chat/{serverId}/…` (root-relative paths only).
- SPA sync starts/stops the service for the URL-active HTTPS server only.
- Bearer tokens stay in memory for the service process; not written to prefs
  or backup (`allowBackup=false`).

#### How it works

```
Chatto server  <--- /api/realtime (protobuf WebSocket) ---
                                                      \
                                         Android foreground service
                                                      |
                                         NotificationManager (OS banner)
                                                      |
                                         tap → open SPA at room/DM url
```

1. After sign-in, JS calls `__CHATTO_NOTIFICATIONS_START` with server URL,
   bearer token, and local `serverId`.
2. An Android **foreground service** starts (ongoing status notification:
   “Connected for notifications”).
3. The service opens `/api/realtime`, sends a v4 subscribe (bearer +
   live-only), hydrates created notification IDs via ConnectRPC, and
   reconnects with backoff on failure.
4. **App UI active:** OS message banners suppressed (in-app realtime wins).
5. **App backgrounded:** non-silent creates become native banners with a
   root-relative deep-link path.
6. **User taps notification:** open MainActivity and navigate the SPA.
7. On logout: `__CHATTO_NOTIFICATIONS_STOP` (sends an explicit STOP action).

#### Files

- `android/.../notifications/ChattoNotificationService.java`
- `android/.../notifications/ChattoNotificationsPlugin.java`
- `android/.../notifications/RealtimeSession.java`
- `android/.../notifications/ProtoWire.java`
- `src/notifications.ts` — window bridge + tap routing

#### Constraints (honest)

- Android shows an ongoing service notification while connected.
- OEMs may still kill the service; document battery-optimization exemptions.
- “Force stop” stops delivery until Chatto is opened again.
- iOS cannot use this model for true background delivery.

### Stage 4 (Bonus) — Native Media Picker

**Native side (this package):** implemented — `src/media-picker.ts` exposes
`window.__CHATTO_PICK_MEDIA` via Capawesome multi-select gallery picker.

**Frontend hook:** still optional — MessageComposer can call the hook when
`window.__CHATTO_CAPACITOR` is set instead of `<input type="file">`.

Dependencies:

- `@capawesome/capacitor-file-picker` (multi-select images/videos)
- Frontend upload hook (optional, outside this package)
## File Structure

```
apps/mobile/
  PLAN.md               # this file
  README.md             # setup and build instructions
  package.json          # workspace package
  capacitor.config.ts   # Capacitor configuration
  tsconfig.json         # TypeScript config for glue code
  .gitignore            # local artefacts only; android/ios stay tracked
  scripts/
    prepare-web.mjs     # stage www/ from frontend build + glue
  src/
    glue.ts             # platform bridge (stage 2)
    notifications.ts    # bridge to Android foreground notification service
    media-picker.ts     # native media picker (stage 4)
    oauth.ts            # optional WebView capture + endCapture hooks
  www/                  # staged webDir, gitignored
  android/              # Capacitor Android project (committed)
    app/src/main/java/.../notifications/
      ChattoNotificationService.java
      ChattoNotificationsPlugin.java
      RealtimeSession.java
      ProtoWire.java
    app/src/main/java/.../oauth/
      ChattoAuthorizationPlugin.java  # Custom Tabs + mobile callback
      ChattoOAuthPlugin.java
  ios/                  # Capacitor iOS project (committed)
```

## Dependencies on Work Outside apps/mobile/

These are noted for planning but are separate efforts:

| Dependency | Stage | Description |
|---|---|---|
| Frontend upload hook | 4 | Optional: detect Capacitor and call native picker |

## Decisions (closed)

1. **Server URL first-run** — keep the existing frontend server-add / login
   flow. No mobile-specific onboarding for now.
2. **Notification copy** — OS banners never show actor names, room names, or
   message previews. Title `Chatto`, body `New message` only (message content
   is encrypted; the native shell does not decrypt).
3. **Multi-server** — notify for the URL-active authenticated server only. If
   that server has no usable bearer token, stop the native service (do not
   fall back to another server).
