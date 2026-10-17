package app.chatto.mobile.oauth;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.browser.customtabs.CustomTabsIntent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Tabs OAuth for the official mobile client identity, matching iOS
 * {@code ChattoAuthorization} / {@code ASWebAuthenticationSession}.
 */
@CapacitorPlugin(name = "ChattoAuthorization")
public class ChattoAuthorizationPlugin extends Plugin {

  static final String MOBILE_CLIENT_ID = "eu.chattocorp.chatto.mobile";
  static final String MOBILE_CALLBACK = "eu.chattocorp.chatto.mobile:/oauth/callback";
  private static final long TIMEOUT_MS = 300_000L;
  /** Let {@link #handleOnNewIntent} win over resume when the callback arrives. */
  private static final long CANCEL_GRACE_MS = 400L;

  private PluginCall pending;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private Runnable timeout;
  private Runnable cancelOnResume;
  private boolean awaitingReturnFromCustomTabs;

  @PluginMethod
  public void authorize(PluginCall call) {
    mainHandler.post(() -> start(call));
  }

  private void start(PluginCall call) {
    if (pending != null) {
      call.reject("A sign-in attempt is already active.");
      return;
    }

    String raw = call.getString("url");
    if (!isValidAuthorizeUrl(raw)) {
      call.reject("Sign-in requires an HTTPS server and the mobile callback.");
      return;
    }

    Uri url = Uri.parse(raw);
    pending = call;
    awaitingReturnFromCustomTabs = false;
    timeout =
        () -> {
          if (pending != call) {
            return;
          }
          clearPending();
          call.reject("The sign-in attempt timed out.");
        };
    mainHandler.postDelayed(timeout, TIMEOUT_MS);

    CustomTabsIntent tabs = new CustomTabsIntent.Builder().build();
    tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
    try {
      tabs.launchUrl(getContext(), url);
      awaitingReturnFromCustomTabs = true;
    } catch (RuntimeException e) {
      clearPending();
      call.reject("The sign-in session could not be started.");
    }
  }

  @Override
  protected void handleOnNewIntent(Intent intent) {
    super.handleOnNewIntent(intent);
    if (pending == null || intent == null) {
      return;
    }
    Uri data = intent.getData();
    if (data == null || !isValidCallbackUri(data.toString())) {
      return;
    }

    PluginCall call = pending;
    clearPending();
    JSObject result = new JSObject();
    result.put("url", data.toString());
    call.resolve(result);
    intent.setData(null);
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    if (!awaitingReturnFromCustomTabs || pending == null) {
      return;
    }
    final PluginCall call = pending;
    if (cancelOnResume != null) {
      mainHandler.removeCallbacks(cancelOnResume);
    }
    cancelOnResume =
        () -> {
          cancelOnResume = null;
          if (pending != call) {
            return;
          }
          clearPending();
          call.reject("Sign-in was cancelled or could not be completed.");
        };
    mainHandler.postDelayed(cancelOnResume, CANCEL_GRACE_MS);
  }

  private void clearPending() {
    if (timeout != null) {
      mainHandler.removeCallbacks(timeout);
      timeout = null;
    }
    if (cancelOnResume != null) {
      mainHandler.removeCallbacks(cancelOnResume);
      cancelOnResume = null;
    }
    pending = null;
    awaitingReturnFromCustomTabs = false;
  }

  /** HTTPS authorize URL with exactly the official mobile client and callback. */
  static boolean isValidAuthorizeUrl(String raw) {
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    final URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException e) {
      return false;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getHost().isEmpty()
        || uri.getUserInfo() != null) {
      return false;
    }
    Map<String, List<String>> query = parseQuery(uri.getRawQuery());
    List<String> clientIds = query.get("client_id");
    List<String> redirects = query.get("redirect_uri");
    return clientIds != null
        && clientIds.size() == 1
        && MOBILE_CLIENT_ID.equals(clientIds.get(0))
        && redirects != null
        && redirects.size() == 1
        && MOBILE_CALLBACK.equals(redirects.get(0));
  }

  /** Official mobile callback URI (optional query; no fragment/host). */
  static boolean isValidCallbackUri(String raw) {
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    final URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException e) {
      return false;
    }
    if (!MOBILE_CLIENT_ID.equals(uri.getScheme())) {
      return false;
    }
    if (uri.getHost() != null && !uri.getHost().isEmpty()) {
      return false;
    }
    if (uri.getFragment() != null) {
      return false;
    }
    String path = uri.getPath();
    return "/oauth/callback".equals(path);
  }

  private static Map<String, List<String>> parseQuery(String rawQuery) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return out;
    }
    for (String part : rawQuery.split("&")) {
      if (part.isEmpty()) {
        continue;
      }
      int eq = part.indexOf('=');
      String key = eq >= 0 ? part.substring(0, eq) : part;
      String value = eq >= 0 ? part.substring(eq + 1) : "";
      key = urlDecode(key);
      value = urlDecode(value);
      List<String> values = out.get(key);
      if (values == null) {
        values = new ArrayList<>(1);
        out.put(key, values);
      }
      values.add(value);
    }
    return out;
  }

  private static String urlDecode(String value) {
    try {
      return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return value;
    }
  }
}
