package app.chatto.mobile.notifications;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Hydrates one notification occurrence through ConnectRPC JSON unary.
 *
 * <p>Path: {@code /chatto.api.v1.NotificationService/GetNotificationOccurrence}.
 */
final class NotificationOccurrenceClient {
  private static final String TAG = "ChattoNotifFetch";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final String RPC_PATH =
      "/chatto.api.v1.NotificationService/GetNotificationOccurrence";

  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(10, TimeUnit.SECONDS)
          .build();

  ProtoWire.NotificationCreate fetch(String serverUrl, String bearerToken, String notificationId)
      throws IOException {
    String origin = trimTrailingSlash(serverUrl);
    JSONObject body = new JSONObject();
    try {
      body.put("notificationId", notificationId);
    } catch (Exception e) {
      throw new IOException("encode request", e);
    }

    Request request =
        new Request.Builder()
            .url(origin + RPC_PATH)
            .header("Authorization", "Bearer " + bearerToken)
            .header("Connect-Protocol-Version", "1")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

    try (Response response = client.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String text = responseBody != null ? responseBody.string() : "";
      if (!response.isSuccessful()) {
        throw new IOException("GetNotificationOccurrence HTTP " + response.code());
      }
      return parseOccurrence(notificationId, text);
    }
  }

  private static ProtoWire.NotificationCreate parseOccurrence(String fallbackId, String jsonText)
      throws IOException {
    try {
      JSONObject root = new JSONObject(jsonText);
      JSONObject occurrence = root.optJSONObject("occurrence");
      if (occurrence == null) {
        throw new IOException("missing occurrence");
      }
      String id = occurrence.optString("id", fallbackId);
      boolean unread = occurrence.optBoolean("unread", true);
      String actorDisplay = actorDisplay(occurrence.optJSONObject("actor"));
      SignalFields signal = signalFields(occurrence.optJSONObject("signal"));
      // Already-read creates (for example during DND catch-up) stay silent.
      boolean silent = !unread;
      ProtoWire.NotificationItem item =
          new ProtoWire.NotificationItem(
              id, actorDisplay, signal.roomId, signal.threadRootId, signal.kindLabel);
      return new ProtoWire.NotificationCreate(id, silent, item);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("parse occurrence", e);
    }
  }

  private static String actorDisplay(JSONObject actor) {
    if (actor == null) {
      return "";
    }
    String displayName = actor.optString("displayName", "");
    if (!displayName.isEmpty()) {
      return displayName;
    }
    return actor.optString("login", "");
  }

  private static final class SignalFields {
    final String roomId;
    final String threadRootId;
    final String kindLabel;

    SignalFields(String roomId, String threadRootId, String kindLabel) {
      this.roomId = roomId;
      this.threadRootId = threadRootId;
      this.kindLabel = kindLabel;
    }
  }

  private static SignalFields signalFields(JSONObject signal) {
    if (signal == null) {
      return new SignalFields(null, null, "New notification");
    }
    Iterator<String> keys = signal.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONObject wrapper = signal.optJSONObject(key);
      if (wrapper == null) {
        continue;
      }
      JSONObject message = wrapper.optJSONObject("message");
      String roomId = null;
      String threadRootId = null;
      if (message != null) {
        JSONObject room = message.optJSONObject("room");
        if (room != null) {
          roomId = emptyToNull(room.optString("id", ""));
        }
        threadRootId = emptyToNull(message.optString("threadRootEventId", ""));
      }
      return new SignalFields(roomId, threadRootId, kindLabelForSignal(key));
    }
    return new SignalFields(null, null, "New notification");
  }

  private static String kindLabelForSignal(String signalCase) {
    switch (signalCase) {
      case "directMessageReceived":
        return "Direct message";
      case "directMentionReceived":
        return "Mention";
      case "replyReceived":
        return "Reply";
      case "roleMentionReceived":
        return "Role mention";
      case "hereMentionReceived":
        return "@here";
      case "allMentionReceived":
        return "@all";
      case "followedThreadActivity":
        return "Followed thread";
      case "reactionReceived":
        return "Reaction";
      case "roomMessageReceived":
        return "New message";
      default:
        Log.i(TAG, "unknown signal " + signalCase);
        return "New notification";
    }
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
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
