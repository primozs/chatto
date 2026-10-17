package app.chatto.mobile.oauth;

import android.net.Uri;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Optional HTTPS navigation capture inside the Capacitor WebView.
 *
 * Remote reauth uses {@link ChattoAuthorizationPlugin} (Custom Tabs). This
 * plugin remains for any legacy full-page HTTPS authorize hops that still
 * need to stay in-WebView; call {@code endCapture} when finished.
 */
@CapacitorPlugin(name = "ChattoOAuth")
public class ChattoOAuthPlugin extends Plugin {

  private volatile boolean capturing = false;

  @PluginMethod
  public void beginCapture(PluginCall call) {
    capturing = true;
    call.resolve();
  }

  @PluginMethod
  public void endCapture(PluginCall call) {
    capturing = false;
    call.resolve();
  }

  /**
   * @return {@code false} to load in the WebView, {@code null} for default
   * Capacitor handling, {@code true} if the navigation was handled externally.
   */
  @Override
  public Boolean shouldOverrideLoad(Uri url) {
    if (!capturing || url == null) {
      return null;
    }

    String scheme = url.getScheme();
    if (scheme == null) {
      return null;
    }
    // OAuth capture is HTTPS-only; cleartext IdP hops must not stay in-WebView.
    if (!"https".equalsIgnoreCase(scheme)) {
      return null;
    }

    if (isAppOrigin(url)) {
      capturing = false;
      return false;
    }
    // Stay in the WebView for authorize, remote login, IdP hops, and callback.
    return false;
  }

  private boolean isAppOrigin(Uri url) {
    if (bridge == null) {
      return false;
    }
    String appUrl = bridge.getAppUrl();
    if (appUrl == null || appUrl.isEmpty()) {
      return false;
    }
    Uri appUri = Uri.parse(appUrl);
    String appHost = appUri.getHost();
    String appScheme = appUri.getScheme();
    String host = url.getHost();
    return appHost != null
        && host != null
        && appHost.equalsIgnoreCase(host)
        && appScheme != null
        && appScheme.equalsIgnoreCase(url.getScheme());
  }
}
