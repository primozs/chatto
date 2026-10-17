package app.chatto.mobile.notifications;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Maintains an authenticated {@code /api/realtime} WebSocket (protocol v4) and
 * hydrates created notification IDs for the foreground service.
 */
final class RealtimeSession {
  interface Listener {
    void onNotificationCreate(ProtoWire.NotificationCreate create);

    void onConnectionState(boolean connected);
  }

  private static final String TAG = "ChattoRealtime";
  private static final long INITIAL_BACKOFF_MS = 1_000L;
  private static final long MAX_BACKOFF_MS = 60_000L;

  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .pingInterval(20, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS)
          .build();
  private final NotificationOccurrenceClient occurrenceClient = new NotificationOccurrenceClient();
  private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Listener listener;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong backoffMs = new AtomicLong(INITIAL_BACKOFF_MS);

  private String serverUrl;
  private String bearerToken;
  private WebSocket socket;
  private final Runnable reconnectRunnable = this::connectNow;

  RealtimeSession(Listener listener) {
    this.listener = listener;
  }

  void start(String serverUrl, String bearerToken) {
    this.serverUrl = trimTrailingSlash(serverUrl);
    this.bearerToken = bearerToken;
    running.set(true);
    backoffMs.set(INITIAL_BACKOFF_MS);
    connectNow();
  }

  void stop() {
    running.set(false);
    mainHandler.removeCallbacks(reconnectRunnable);
    WebSocket ws = socket;
    socket = null;
    if (ws != null) {
      ws.close(1000, "stop");
    }
    listener.onConnectionState(false);
  }

  private void connectNow() {
    if (!running.get() || serverUrl == null || bearerToken == null) {
      return;
    }
    mainHandler.removeCallbacks(reconnectRunnable);
    WebSocket existing = socket;
    if (existing != null) {
      socket = null;
      existing.cancel();
    }

    String wsUrl = toRealtimeWsUrl(serverUrl);
    Request request = new Request.Builder().url(wsUrl).build();
    Log.i(TAG, "connecting " + wsUrl);
    final String token = bearerToken;
    final String origin = serverUrl;
    socket =
        client.newWebSocket(
            request,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(ByteString.of(ProtoWire.encodeRealtimeSubscribe(token)));
                backoffMs.set(INITIAL_BACKOFF_MS);
                listener.onConnectionState(true);
              }

              @Override
              public void onMessage(WebSocket webSocket, ByteString bytes) {
                byte[] payload = bytes.toByteArray();
                try {
                  if (ProtoWire.isCloseFrame(payload)) {
                    Log.w(TAG, "server close frame");
                    webSocket.close(1000, "close");
                    return;
                  }
                  List<String> createdIds = ProtoWire.extractCreatedNotificationIds(payload);
                  for (String notificationId : createdIds) {
                    hydrateAndDeliver(origin, token, notificationId);
                  }
                } catch (Exception e) {
                  Log.w(TAG, "frame parse failed: " + e.getMessage());
                }
              }

              @Override
              public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
              }

              @Override
              public void onClosed(WebSocket webSocket, int code, String reason) {
                handleDisconnect();
              }

              @Override
              public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "socket failure: " + t.getMessage());
                handleDisconnect();
              }
            });
  }

  private void hydrateAndDeliver(String origin, String token, String notificationId) {
    fetchExecutor.execute(
        () -> {
          if (!running.get()) {
            return;
          }
          try {
            ProtoWire.NotificationCreate create =
                occurrenceClient.fetch(origin, token, notificationId);
            if (!running.get()) {
              return;
            }
            mainHandler.post(() -> listener.onNotificationCreate(create));
          } catch (Exception e) {
            Log.w(TAG, "hydrate " + notificationId + ": " + e.getMessage());
          }
        });
  }

  private void handleDisconnect() {
    listener.onConnectionState(false);
    socket = null;
    if (!running.get()) {
      return;
    }
    long delay = backoffMs.get();
    long next = Math.min(MAX_BACKOFF_MS, delay * 2);
    backoffMs.set(next);
    mainHandler.postDelayed(reconnectRunnable, delay);
  }

  private static String toRealtimeWsUrl(String httpOrigin) {
    String origin = trimTrailingSlash(httpOrigin);
    if (origin.startsWith("https://")) {
      return "wss://" + origin.substring("https://".length()) + "/api/realtime";
    }
    if (origin.startsWith("http://")) {
      return "ws://" + origin.substring("http://".length()) + "/api/realtime";
    }
    return origin + "/api/realtime";
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
}
