package app.chatto.mobile.notifications;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Capacitor bridge for {@link ChattoNotificationService}.
 */
@CapacitorPlugin(
    name = "ChattoNotifications",
    permissions = {
      @Permission(
          alias = "notifications",
          strings = {Manifest.permission.POST_NOTIFICATIONS})
    })
public class ChattoNotificationsPlugin extends Plugin {

  @Override
  protected void handleOnNewIntent(Intent intent) {
    super.handleOnNewIntent(intent);
    emitOpenFromIntent(intent);
  }

  @Override
  public void load() {
    Intent intent = getActivity() != null ? getActivity().getIntent() : null;
    if (intent != null) {
      emitOpenFromIntent(intent);
    }
  }

  private void emitOpenFromIntent(Intent intent) {
    if (intent == null) {
      return;
    }
    if (!ChattoNotificationService.ACTION_OPEN.equals(intent.getAction())) {
      return;
    }
    String gate = intent.getStringExtra(ChattoNotificationService.EXTRA_OPEN_GATE);
    String url = intent.getStringExtra(ChattoNotificationService.EXTRA_OPEN_URL);
    intent.setAction(null);
    intent.removeExtra(ChattoNotificationService.EXTRA_OPEN_URL);
    intent.removeExtra(ChattoNotificationService.EXTRA_OPEN_GATE);
    if (!ChattoNotificationService.consumeOpenGate(gate)) {
      return;
    }
    if (url == null || !NotificationOpenPath.isAllowed(url)) {
      return;
    }
    JSObject data = new JSObject();
    data.put("url", url);
    notifyListeners("notificationOpen", data, true);
  }

  @PluginMethod
  public void start(PluginCall call) {
    String serverUrl = call.getString("serverUrl");
    String bearerToken = call.getString("bearerToken");
    String serverId = call.getString("serverId");
    if (serverUrl == null || serverUrl.isEmpty()) {
      call.reject("serverUrl is required");
      return;
    }
    if (!serverUrl.regionMatches(true, 0, "https://", 0, 8)) {
      call.reject("serverUrl must be https");
      return;
    }
    if (bearerToken == null || bearerToken.isEmpty()) {
      call.reject("bearerToken is required");
      return;
    }
    if (serverId == null || serverId.isEmpty()) {
      call.reject("serverId is required");
      return;
    }

    if (Build.VERSION.SDK_INT >= 33
        && ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissionForAlias("notifications", call, "notificationPermsCallback");
      return;
    }

    ChattoNotificationService.start(getContext(), serverUrl, bearerToken, serverId);
    call.resolve();
  }

  @PermissionCallback
  private void notificationPermsCallback(PluginCall call) {
    if (getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED) {
      call.reject("Notification permission denied");
      return;
    }
    String serverUrl = call.getString("serverUrl");
    String bearerToken = call.getString("bearerToken");
    String serverId = call.getString("serverId");
    if (serverUrl == null || serverUrl.isEmpty()) {
      call.reject("serverUrl is required");
      return;
    }
    if (!serverUrl.regionMatches(true, 0, "https://", 0, 8)) {
      call.reject("serverUrl must be https");
      return;
    }
    if (bearerToken == null || bearerToken.isEmpty()) {
      call.reject("bearerToken is required");
      return;
    }
    if (serverId == null || serverId.isEmpty()) {
      call.reject("serverId is required");
      return;
    }
    ChattoNotificationService.start(getContext(), serverUrl, bearerToken, serverId);
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    ChattoNotificationService.stop(getContext());
    call.resolve();
  }

  @PluginMethod
  public void setUiActive(PluginCall call) {
    boolean active = Boolean.TRUE.equals(call.getBoolean("active", true));
    ChattoNotificationService.setUiActive(getContext(), active);
    call.resolve();
  }
}
