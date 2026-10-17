/**
 * Bridge from the SPA auth state to the Capacitor foreground notification
 * service (`window.__CHATTO_NOTIFICATIONS_*` installed by apps/mobile glue).
 */

import { isCapacitorShell } from './shell';

export { isCapacitorShell } from './shell';

export type NativeNotificationTarget = {
  serverId: string;
  serverUrl: string;
  bearerToken: string;
};

type RegisteredServerLike = {
  id: string;
  url: string;
  token: string | null;
  reauthRequiredAt: number | null;
};

declare global {
  interface Window {
    __CHATTO_NOTIFICATIONS_START?: (options: NativeNotificationTarget) => Promise<void>;
    __CHATTO_NOTIFICATIONS_STOP?: () => Promise<void>;
    __CHATTO_NOTIFICATIONS_SET_UI_ACTIVE?: (active: boolean) => Promise<void>;
  }
}

/**
 * Pick the URL-active authenticated server for the native service.
 * Returns null when the active server has no usable bearer token (no fallback).
 */
export function selectNativeNotificationTarget(
  servers: RegisteredServerLike[],
  activeServerId: string | undefined
): NativeNotificationTarget | null {
  if (activeServerId === undefined || activeServerId === '') return null;

  const active = servers.find((server) => server.id === activeServerId);
  if (
    !active ||
    active.token == null ||
    active.token.length === 0 ||
    active.reauthRequiredAt != null
  ) {
    return null;
  }

  let origin: string;
  try {
    const parsed = new URL(active.url);
    if (parsed.protocol !== 'https:') return null;
    origin = parsed.origin;
  } catch {
    return null;
  }

  return {
    serverId: active.id,
    serverUrl: origin,
    bearerToken: active.token
  };
}

let lastSyncedKey: string | null = null;

/** Dispatched by apps/mobile glue once `__CHATTO_NOTIFICATIONS_*` hooks exist. */
export const NATIVE_NOTIFICATIONS_READY_EVENT = 'chatto:native-notifications-ready';

/** Test helper — clears the last-synced fingerprint. */
export function resetNativeNotificationSyncState(): void {
  lastSyncedKey = null;
}

/**
 * Start, restart, or stop the native service when the target changes.
 * No-ops outside the Capacitor shell.
 *
 * Does not fingerprint a start until the glue bridge is present and the native
 * plugin call resolves — otherwise a race with late glue install (or a denied
 * permission prompt) would permanently skip retries for the same target.
 */
export async function syncNativeNotificationService(
  target: NativeNotificationTarget | null
): Promise<void> {
  if (!isCapacitorShell()) return;

  const key = target
    ? `${target.serverId}\0${target.serverUrl}\0${target.bearerToken}`
    : null;
  if (key === lastSyncedKey) return;

  if (!target) {
    lastSyncedKey = null;
    await window.__CHATTO_NOTIFICATIONS_STOP?.();
    return;
  }

  const start = window.__CHATTO_NOTIFICATIONS_START;
  if (!start) {
    // Glue not ready yet — leave unsynced so a later effect / ready event retries.
    return;
  }

  try {
    await start({
      serverId: target.serverId,
      serverUrl: target.serverUrl,
      bearerToken: target.bearerToken
    });
    lastSyncedKey = key;
  } catch {
    // Permission denied or plugin failure — leave unsynced for a later retry.
  }
}
