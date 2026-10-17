package app.chatto.mobile.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import app.chatto.mobile.MainActivity;
import app.chatto.mobile.R;

/**
 * Foreground service that keeps an authenticated {@code /api/realtime}
 * WebSocket and posts OS banners for non-silent notification creates when the
 * WebView UI is not active.
 *
 * No FCM / Google Play Services / external push relay.
 */
public class ChattoNotificationService extends Service implements RealtimeSession.Listener {
  public static final String ACTION_START = "app.chatto.mobile.notifications.START";
  public static final String ACTION_STOP = "app.chatto.mobile.notifications.STOP";
  public static final String ACTION_SET_UI_ACTIVE =
      "app.chatto.mobile.notifications.SET_UI_ACTIVE";
  public static final String ACTION_OPEN = "app.chatto.mobile.notifications.OPEN";

  public static final String EXTRA_SERVER_URL = "serverUrl";
  public static final String EXTRA_BEARER_TOKEN = "bearerToken";
  public static final String EXTRA_SERVER_ID = "serverId";
  public static final String EXTRA_UI_ACTIVE = "uiActive";
  public static final String EXTRA_OPEN_URL = "openUrl";
  /** One-time gate so foreign apps cannot forge {@link #ACTION_OPEN} intents. */
  public static final String EXTRA_OPEN_GATE = "openGate";

  private static final String TAG = "ChattoNotifyService";
  private static final java.util.Set<String> OPEN_GATES =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final String CHANNEL_STATUS = "chatto_status";
  private static final String CHANNEL_ALERTS = "chatto_alerts";
  private static final int STATUS_NOTIFICATION_ID = 1001;

  private RealtimeSession session;
  private String serverUrl;
  private String bearerToken;
  private String serverId;
  private boolean uiActive = true;
  private boolean realtimeConnected;

  @Override
  public void onCreate() {
    super.onCreate();
    ensureChannels();
    session = new RealtimeSession(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Do not restart with a null Intent: credentials live only in memory for
    // this process lifetime (never SharedPreferences / backup).
    if (intent == null) {
      stopSelfSafe();
      return START_NOT_STICKY;
    }

    String action = intent.getAction();
    if (ACTION_STOP.equals(action)) {
      stopSelfSafe();
      return START_NOT_STICKY;
    }

    if (ACTION_SET_UI_ACTIVE.equals(action)) {
      if (serverUrl == null) {
        stopSelf();
        return START_NOT_STICKY;
      }
      uiActive = intent.getBooleanExtra(EXTRA_UI_ACTIVE, true);
      return START_STICKY;
    }

    if (ACTION_START.equals(action)) {
      serverUrl = trimTrailingSlash(intent.getStringExtra(EXTRA_SERVER_URL));
      bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN);
      serverId = intent.getStringExtra(EXTRA_SERVER_ID);
      if (serverUrl == null || bearerToken == null || serverId == null) {
        Log.w(TAG, "start missing credentials");
        stopSelfSafe();
        return START_NOT_STICKY;
      }
      if (!serverUrl.regionMatches(true, 0, "https://", 0, 8)) {
        Log.w(TAG, "start requires https serverUrl");
        stopSelfSafe();
        return START_NOT_STICKY;
      }
      startAsForeground();
      session.start(serverUrl, bearerToken);
      return START_STICKY;
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    if (session != null) {
      session.stop();
    }
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onNotificationCreate(ProtoWire.NotificationCreate create) {
    if (create.silent || uiActive) {
      return;
    }
    postAlert(create.item);
  }

  @Override
  public void onConnectionState(boolean connected) {
    realtimeConnected = connected;
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.notify(STATUS_NOTIFICATION_ID, buildStatusNotification());
  }

  private void startAsForeground() {
    Notification status = buildStatusNotification();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(
          this,
          STATUS_NOTIFICATION_ID,
          status,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(STATUS_NOTIFICATION_ID, status);
    }
  }

  private void stopSelfSafe() {
    if (session != null) {
      session.stop();
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  private void ensureChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel status =
        new NotificationChannel(
            CHANNEL_STATUS, "Chatto connection", NotificationManager.IMPORTANCE_LOW);
    status.setDescription("Shown while Chatto keeps a realtime notification connection open");
    NotificationChannel alerts =
        new NotificationChannel(
            CHANNEL_ALERTS, "Chatto messages", NotificationManager.IMPORTANCE_HIGH);
    alerts.setDescription("New chat notifications while the app is in the background");
    nm.createNotificationChannel(status);
    nm.createNotificationChannel(alerts);
  }

  private Notification buildStatusNotification() {
    Intent open = new Intent(this, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pi =
        PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text =
        realtimeConnected
            ? "Connected for notifications"
            : "Reconnecting for notifications…";
    return new NotificationCompat.Builder(this, CHANNEL_STATUS)
        .setContentTitle("Chatto")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(pi)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build();
  }

  private void postAlert(ProtoWire.NotificationItem item) {
    // OS banners must not reveal actors, room names, or message content —
    // bodies are encrypted and the native shell does not decrypt.
    String url = buildPath(serverId, item.roomId, item.threadRootId);
    if (!NotificationOpenPath.isAllowed(url)) {
      return;
    }
    String gate = java.util.UUID.randomUUID().toString();
    OPEN_GATES.add(gate);
    String title = "Chatto";
    String body = "New message";

    Intent open = new Intent(this, MainActivity.class);
    open.setAction(ACTION_OPEN);
    open.putExtra(EXTRA_OPEN_URL, url);
    open.putExtra(EXTRA_OPEN_GATE, gate);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pi =
        PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Notification alert =
        new NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.notify(item.id.hashCode(), alert);
  }

  static String buildPath(String serverId, String roomId, String threadRootId) {
    return NotificationOpenPath.buildPath(serverId, roomId, threadRootId);
  }

  /** Consume a one-time open gate issued when posting a banner. */
  static boolean consumeOpenGate(String gate) {
    return gate != null && !gate.isEmpty() && OPEN_GATES.remove(gate);
  }

  /** Test helper — register a gate as {@link #postAlert} would. */
  static void registerOpenGate(String gate) {
    if (gate != null && !gate.isEmpty()) {
      OPEN_GATES.add(gate);
    }
  }

  private static String trimTrailingSlash(String url) {
    if (url == null) {
      return null;
    }
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  public static void start(
      Context context, String serverUrl, String bearerToken, String serverId) {
    Intent intent = new Intent(context, ChattoNotificationService.class);
    intent.setAction(ACTION_START);
    intent.putExtra(EXTRA_SERVER_URL, serverUrl);
    intent.putExtra(EXTRA_BEARER_TOKEN, bearerToken);
    intent.putExtra(EXTRA_SERVER_ID, serverId);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  public static void stop(Context context) {
    Intent intent = new Intent(context, ChattoNotificationService.class);
    intent.setAction(ACTION_STOP);
    try {
      context.startService(intent);
    } catch (IllegalStateException e) {
      context.stopService(new Intent(context, ChattoNotificationService.class));
    }
  }

  public static void setUiActive(Context context, boolean active) {
    Intent intent = new Intent(context, ChattoNotificationService.class);
    intent.setAction(ACTION_SET_UI_ACTIVE);
    intent.putExtra(EXTRA_UI_ACTIVE, active);
    try {
      context.startService(intent);
    } catch (IllegalStateException ignored) {
      // No running service to update.
    }
  }
}
