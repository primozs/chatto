/**
 * Capacitor ↔ web bridge. Bundled into www/capacitor-glue.js and loaded
 * before the SvelteKit SPA so native chrome and optional server URL seeding
 * run on first paint.
 */

import { Capacitor } from '@capacitor/core';
import { Keyboard, KeyboardResize } from '@capacitor/keyboard';
import { Preferences } from '@capacitor/preferences';
import { StatusBar, Style } from '@capacitor/status-bar';
import { installMediaPicker } from './media-picker';
import { installNotificationBridge } from './notifications';
import { installOAuthBridge } from './oauth';

declare global {
  interface Window {
    /** True when running inside the Capacitor native shell. */
    __CHATTO_CAPACITOR?: boolean;
    /**
     * Persist a preferred Chatto server URL in native Preferences and, when
     * the SPA registry is empty, seed localStorage so the next load connects.
     */
    __CHATTO_SET_SERVER_URL?: (url: string) => Promise<void>;
  }
}

const PREFERENCES_SERVER_URL_KEY = 'chatto.serverUrl';
const INSTANCES_STORAGE_KEY = 'chatto:instances';

function generateServerId(url: string): string {
  let hostname: string;
  try {
    hostname = new URL(url).hostname;
  } catch {
    hostname = url.replace(/[^a-z0-9-]/gi, '-');
  }
  return hostname.replace(/\./g, '-').replace(/^-+|-+$/g, '') || 'server';
}

function hasRegisteredServers(): boolean {
  try {
    const raw = localStorage.getItem(INSTANCES_STORAGE_KEY);
    if (!raw) return false;
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0;
  } catch {
    return false;
  }
}

function seedServerRegistry(url: string): void {
  if (hasRegisteredServers()) return;

  let normalized: string;
  try {
    normalized = new URL(url).origin;
  } catch {
    return;
  }

  const entry = {
    id: generateServerId(normalized),
    url: normalized,
    name: 'Chatto',
    iconUrl: null,
    token: null,
    userId: null,
    userLogin: null,
    userDisplayName: null,
    userAvatarUrl: null,
    reauthRequiredAt: null,
    addedAt: Date.now()
  };

  try {
    localStorage.setItem(INSTANCES_STORAGE_KEY, JSON.stringify([entry]));
  } catch {
    // Quota / privacy mode — leave the add-server flow to handle it.
  }
}

async function applyPreferredServerUrl(): Promise<void> {
  const { value } = await Preferences.get({ key: PREFERENCES_SERVER_URL_KEY });
  if (!value) return;
  seedServerRegistry(value);
}

async function setPreferredServerUrl(url: string): Promise<void> {
  let normalized: string;
  try {
    normalized = new URL(url).origin;
  } catch {
    throw new Error('invalid server URL');
  }
  await Preferences.set({ key: PREFERENCES_SERVER_URL_KEY, value: normalized });
  seedServerRegistry(normalized);
}

function syncSafeAreaCssVars(): void {
  const root = document.documentElement;
  for (const side of ['top', 'right', 'bottom', 'left'] as const) {
    root.style.setProperty(
      `--safe-area-inset-${side}`,
      `env(safe-area-inset-${side}, 0px)`
    );
  }
}

function statusBarStyleFromTheme(): Style {
  const theme = document.documentElement.dataset.theme;
  return theme === 'dark' ? Style.Light : Style.Dark;
}

async function configureStatusBar(): Promise<void> {
  if (Capacitor.getPlatform() === 'web') return;
  try {
    await StatusBar.setOverlaysWebView({ overlay: true });
    await StatusBar.setStyle({ style: statusBarStyleFromTheme() });
  } catch {
    // Plugin unavailable — ignore.
  }

  const observer = new MutationObserver(() => {
    void StatusBar.setStyle({ style: statusBarStyleFromTheme() }).catch(() => {});
  });
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme']
  });
}

async function configureKeyboard(): Promise<void> {
  if (Capacitor.getPlatform() === 'web') return;
  try {
    await Keyboard.setResizeMode({ mode: KeyboardResize.Body });
  } catch {
    // Plugin unavailable — ignore.
  }

  const root = document.documentElement;
  Keyboard.addListener('keyboardWillShow', (info) => {
    root.style.setProperty('--keyboard-inset-height', `${info.keyboardHeight}px`);
  });
  Keyboard.addListener('keyboardDidHide', () => {
    root.style.setProperty('--keyboard-inset-height', '0px');
  });
}

async function main(): Promise<void> {
  window.__CHATTO_CAPACITOR = true;
  window.__CHATTO_SET_SERVER_URL = setPreferredServerUrl;
  installMediaPicker();
  installOAuthBridge();

  syncSafeAreaCssVars();
  await applyPreferredServerUrl();
  await configureStatusBar();
  await configureKeyboard();
  await installNotificationBridge();
}

void main();
