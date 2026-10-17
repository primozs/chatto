/**
 * Capacitor shell detection and window bridges installed by apps/mobile glue.
 */

declare global {
  interface Window {
    /** True when running inside the Capacitor native shell. */
    __CHATTO_CAPACITOR?: boolean;
    /**
     * Keep subsequent https top-level navigations in the WebView so remote
     * OAuth authorize → callback can return to this origin.
     */
    __CHATTO_OAUTH_CAPTURE_NAVIGATIONS?: () => Promise<void>;
    /** Release navigation capture after OAuth completes or is abandoned. */
    __CHATTO_OAUTH_END_CAPTURE?: () => Promise<void>;
  }
}

/** True when the Capacitor shell injected its platform flag. */
export function isCapacitorShell(): boolean {
  return typeof window !== 'undefined' && window.__CHATTO_CAPACITOR === true;
}
