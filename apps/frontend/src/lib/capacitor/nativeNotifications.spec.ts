import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  isCapacitorShell,
  resetNativeNotificationSyncState,
  selectNativeNotificationTarget,
  syncNativeNotificationService
} from './nativeNotifications';

type TestWindow = {
  __CHATTO_CAPACITOR?: boolean;
  __CHATTO_NOTIFICATIONS_START?: (options: unknown) => Promise<void>;
  __CHATTO_NOTIFICATIONS_STOP?: () => Promise<void>;
};

const testWindow: TestWindow = {};
vi.stubGlobal('window', testWindow);

describe('selectNativeNotificationTarget', () => {
  const origin = {
    id: 'origin',
    url: 'https://chat.example.com',
    token: 'cht_ATorigin',
    reauthRequiredAt: null
  };
  const remote = {
    id: 'remote',
    url: 'https://remote.example.com',
    token: 'cht_ATremote',
    reauthRequiredAt: null
  };

  it('returns null when no usable tokens exist', () => {
    expect(
      selectNativeNotificationTarget(
        [{ ...origin, token: null }, { ...remote, reauthRequiredAt: Date.now() }],
        'origin'
      )
    ).toBeNull();
  });

  it('returns only the active server when authenticated', () => {
    expect(selectNativeNotificationTarget([origin, remote], 'remote')).toEqual({
      serverId: 'remote',
      serverUrl: 'https://remote.example.com',
      bearerToken: 'cht_ATremote'
    });
  });

  it('does not fall back when the active server is missing or unusable', () => {
    expect(selectNativeNotificationTarget([origin, remote], 'missing')).toBeNull();
    expect(
      selectNativeNotificationTarget(
        [origin, { ...remote, token: null }],
        'remote'
      )
    ).toBeNull();
  });

  it('rejects non-HTTPS server URLs', () => {
    expect(
      selectNativeNotificationTarget(
        [{ ...origin, url: 'http://chat.example.com' }],
        'origin'
      )
    ).toBeNull();
  });
});

describe('syncNativeNotificationService', () => {
  beforeEach(() => {
    resetNativeNotificationSyncState();
    delete testWindow.__CHATTO_CAPACITOR;
    delete testWindow.__CHATTO_NOTIFICATIONS_START;
    delete testWindow.__CHATTO_NOTIFICATIONS_STOP;
  });

  it('no-ops outside Capacitor', async () => {
    const start = vi.fn();
    testWindow.__CHATTO_NOTIFICATIONS_START = start;
    await syncNativeNotificationService({
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok'
    });
    expect(start).not.toHaveBeenCalled();
    expect(isCapacitorShell()).toBe(false);
  });

  it('starts when Capacitor is present and stops when cleared', async () => {
    testWindow.__CHATTO_CAPACITOR = true;
    const start = vi.fn(async () => {});
    const stop = vi.fn(async () => {});
    testWindow.__CHATTO_NOTIFICATIONS_START = start;
    testWindow.__CHATTO_NOTIFICATIONS_STOP = stop;

    const target = {
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok'
    };
    await syncNativeNotificationService(target);
    await syncNativeNotificationService(target);
    expect(start).toHaveBeenCalledTimes(1);
    expect(start).toHaveBeenCalledWith(target);

    await syncNativeNotificationService(null);
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('restarts when the token changes', async () => {
    testWindow.__CHATTO_CAPACITOR = true;
    const start = vi.fn(async () => {});
    testWindow.__CHATTO_NOTIFICATIONS_START = start;

    await syncNativeNotificationService({
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok-1'
    });
    await syncNativeNotificationService({
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok-2'
    });
    expect(start).toHaveBeenCalledTimes(2);
  });

  it('retries after the glue bridge appears', async () => {
    testWindow.__CHATTO_CAPACITOR = true;
    const target = {
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok'
    };

    await syncNativeNotificationService(target);
    const start = vi.fn(async () => {});
    testWindow.__CHATTO_NOTIFICATIONS_START = start;
    await syncNativeNotificationService(target);
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('retries after a failed start', async () => {
    testWindow.__CHATTO_CAPACITOR = true;
    const start = vi
      .fn()
      .mockRejectedValueOnce(new Error('denied'))
      .mockResolvedValueOnce(undefined);
    testWindow.__CHATTO_NOTIFICATIONS_START = start;

    const target = {
      serverId: 'origin',
      serverUrl: 'https://chat.example.com',
      bearerToken: 'tok'
    };
    await syncNativeNotificationService(target);
    await syncNativeNotificationService(target);
    expect(start).toHaveBeenCalledTimes(2);
  });
});
