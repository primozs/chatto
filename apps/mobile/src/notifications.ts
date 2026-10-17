/**
 * Bridge to the Android foreground notification service.
 *
 * Start after sign-in with server URL + bearer token. The native service keeps
 * a realtime WebSocket and hydrates created notification IDs through ConnectRPC,
 * then posts OS banners when the UI is inactive. No FCM / Google Play Services /
 * external push relay.
 */

import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export type NotificationServiceStartOptions = {
  /** Chatto server origin, e.g. https://chat.example.com */
  serverUrl: string;
  /** Opaque bearer token (cht_AT…). */
  bearerToken: string;
  /**
   * Local registry server id (hostname slug). Used in deep-link paths
   * `/chat/{serverId}/…`.
   */
  serverId: string;
};

type ChattoNotificationsPlugin = {
  start(options: NotificationServiceStartOptions): Promise<void>;
  stop(): Promise<void>;
  setUiActive(options: { active: boolean }): Promise<void>;
  addListener(
    eventName: 'notificationOpen',
    listenerFunc: (event: { url: string }) => void
  ): Promise<PluginListenerHandle>;
};

const ChattoNotifications = registerPlugin<ChattoNotificationsPlugin>(
  'ChattoNotifications',
  {
    web: () => ({
      async start() {},
      async stop() {},
      async setUiActive() {},
      async addListener() {
        return { remove: async () => {} };
      }
    })
  }
);

declare global {
  interface Window {
    /** Start the Android foreground notification service. */
    __CHATTO_NOTIFICATIONS_START?: (
      options: NotificationServiceStartOptions
    ) => Promise<void>;
    /** Stop the Android foreground notification service. */
    __CHATTO_NOTIFICATIONS_STOP?: () => Promise<void>;
    /** Tell the service whether the SPA is visible (suppresses OS banners). */
    __CHATTO_NOTIFICATIONS_SET_UI_ACTIVE?: (active: boolean) => Promise<void>;
  }
}

/** Same rules as frontend `$lib/navigation/safeInternalPath`. */
function isSafeInternalPath(value: string): boolean {
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return false;
  }
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index);
    if (codePoint <= 0x1f || codePoint === 0x7f) return false;
  }
  return true;
}

/** Same segment rules as frontend `$lib/capacitor/notificationOpenPath`. */
const CHAT_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

function isAllowedChatPath(pathname: string): boolean {
  if (!pathname.startsWith('/chat/')) return false;
  const segments = pathname.slice('/chat/'.length).split('/');
  if (segments.length < 1 || segments.length > 3) return false;
  return segments.every((segment) => CHAT_SEGMENT.test(segment));
}

/**
 * Normalize a native notification open target to an allowlisted SPA chat path.
 * Mirrors frontend `$lib/capacitor/notificationOpenPath`.
 */
export function resolveNotificationOpenPath(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  const pathOnly = trimmed.split(/[?#]/, 1)[0] ?? trimmed;
  if (!isSafeInternalPath(pathOnly)) return null;
  if (pathOnly.split('/').some((segment) => segment === '.' || segment === '..')) {
    return null;
  }
  try {
    const u = new URL(trimmed, 'https://chatto.invalid');
    if (u.search || u.hash) return null;
    if (u.pathname !== pathOnly) return null;
    if (!isAllowedChatPath(u.pathname)) return null;
    return u.pathname;
  } catch {
    return null;
  }
}

function navigateToPath(rawUrl: string): void {
  const path = resolveNotificationOpenPath(rawUrl);
  if (!path) return;
  window.__CHATTO_PENDING_PUSH_URL = path;
  window.history.pushState(window.history.state, '', path);
  window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }));
}

declare global {
  interface Window {
    __CHATTO_PENDING_PUSH_URL?: string;
  }
}

/** Install window hooks and tap-routing from the native service. */
export async function installNotificationBridge(): Promise<void> {
  window.__CHATTO_NOTIFICATIONS_START = (options) =>
    ChattoNotifications.start(options);
  window.__CHATTO_NOTIFICATIONS_STOP = () => ChattoNotifications.stop();
  window.__CHATTO_NOTIFICATIONS_SET_UI_ACTIVE = (active) =>
    ChattoNotifications.setUiActive({ active });

  if (!Capacitor.isNativePlatform()) return;

  await ChattoNotifications.addListener('notificationOpen', (event) => {
    if (event?.url) navigateToPath(event.url);
  });

  // Suppress OS banners while the WebView is visible; show when hidden.
  const syncVisibility = () => {
    void ChattoNotifications.setUiActive({
      active: document.visibilityState === 'visible'
    });
  };
  document.addEventListener('visibilitychange', syncVisibility);
  syncVisibility();

  window.dispatchEvent(new Event('chatto:native-notifications-ready'));
}
