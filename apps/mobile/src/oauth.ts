/**
 * Bridge so the SPA can keep OAuth authorize navigations inside the WebView
 * for legacy flows. Remote reauth in the mobile shell uses ChattoAuthorization
 * (Custom Tabs) instead; capture should still be ended if it was begun.
 */

import { registerPlugin } from '@capacitor/core';

type ChattoOAuthPlugin = {
  beginCapture(): Promise<void>;
  endCapture(): Promise<void>;
};

const ChattoOAuth = registerPlugin<ChattoOAuthPlugin>('ChattoOAuth', {
  web: () => ({
    async beginCapture() {},
    async endCapture() {}
  })
});

declare global {
  interface Window {
    /**
     * Keep subsequent https top-level navigations in the WebView until the
     * app origin is reached again (OAuth callback).
     */
    __CHATTO_OAUTH_CAPTURE_NAVIGATIONS?: () => Promise<void>;
    /** Release navigation capture after OAuth completes or is abandoned. */
    __CHATTO_OAUTH_END_CAPTURE?: () => Promise<void>;
  }
}

/** Install the OAuth navigation-capture window hooks. */
export function installOAuthBridge(): void {
  window.__CHATTO_OAUTH_CAPTURE_NAVIGATIONS = () => ChattoOAuth.beginCapture();
  window.__CHATTO_OAUTH_END_CAPTURE = () => ChattoOAuth.endCapture();
}
