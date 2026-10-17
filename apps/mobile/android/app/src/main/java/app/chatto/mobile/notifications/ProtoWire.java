package app.chatto.mobile.notifications;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf wire helpers for realtime protocol v4 notification hints.
 *
 * <p>Subscribe is a bare {@code RealtimeSubscribe} message. Server frames are
 * {@code RealtimeServerFrame}. Notification creates arrive as
 * {@code NotificationOccurrencesChangedEvent.created_notification_id}; the
 * service hydrates each ID through ConnectRPC.
 */
final class ProtoWire {
  private ProtoWire() {}

  /** Behavioral protocol version requested by the official web client. */
  static final int PROTOCOL_VERSION = 4;

  /** {@code RealtimeInitialState.REALTIME_INITIAL_STATE_LIVE_ONLY}. */
  static final int INITIAL_STATE_LIVE_ONLY = 1;

  static final class Reader {
    private final byte[] data;
    private int pos;
    private final int end;

    Reader(byte[] data) {
      this(data, 0, data.length);
    }

    Reader(byte[] data, int offset, int length) {
      this.data = data;
      this.pos = offset;
      this.end = offset + length;
    }

    boolean hasMore() {
      return pos < end;
    }

    int readTag() {
      return (int) readVarint();
    }

    long readVarint() {
      long result = 0;
      int shift = 0;
      while (pos < end) {
        byte b = data[pos++];
        result |= (long) (b & 0x7f) << shift;
        if ((b & 0x80) == 0) {
          return result;
        }
        shift += 7;
        if (shift > 63) {
          throw new IllegalArgumentException("varint too long");
        }
      }
      throw new IllegalArgumentException("truncated varint");
    }

    boolean readBool() {
      return readVarint() != 0;
    }

    String readString() {
      return new String(readBytes(), StandardCharsets.UTF_8);
    }

    byte[] readBytes() {
      int len = (int) readVarint();
      if (len < 0 || pos + len > end) {
        throw new IllegalArgumentException("truncated bytes");
      }
      byte[] out = new byte[len];
      System.arraycopy(data, pos, out, 0, len);
      pos += len;
      return out;
    }

    void skipField(int tag) {
      int wire = tag & 0x7;
      switch (wire) {
        case 0: // varint
          readVarint();
          break;
        case 1: // 64-bit
          pos += 8;
          break;
        case 2: // length-delimited
          readBytes();
          break;
        case 5: // 32-bit
          pos += 4;
          break;
        default:
          throw new IllegalArgumentException("unknown wire type " + wire);
      }
    }
  }

  static final class Writer {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void writeTag(int fieldNumber, int wireType) {
      writeVarint(((long) fieldNumber << 3) | wireType);
    }

    void writeVarint(long value) {
      while (true) {
        if ((value & ~0x7fL) == 0) {
          out.write((int) value);
          return;
        }
        out.write((int) ((value & 0x7f) | 0x80));
        value >>>= 7;
      }
    }

    void writeBytes(int fieldNumber, byte[] value) {
      writeTag(fieldNumber, 2);
      writeVarint(value.length);
      out.write(value, 0, value.length);
    }

    void writeString(int fieldNumber, String value) {
      writeBytes(fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    void writeUint32(int fieldNumber, long value) {
      writeTag(fieldNumber, 0);
      writeVarint(value);
    }

    void writeEnum(int fieldNumber, int value) {
      writeUint32(fieldNumber, value);
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }

  /** Encode a bare {@code RealtimeSubscribe} for the first WebSocket message. */
  static byte[] encodeRealtimeSubscribe(String bearerToken) {
    Writer subscribe = new Writer();
    subscribe.writeUint32(1, PROTOCOL_VERSION);
    if (bearerToken != null && !bearerToken.isEmpty()) {
      subscribe.writeString(2, bearerToken);
    }
    subscribe.writeEnum(4, INITIAL_STATE_LIVE_ONLY);
    return subscribe.toByteArray();
  }

  /** Alert payload after Connect hydration. */
  static final class NotificationCreate {
    final String notificationId;
    final boolean silent;
    final NotificationItem item;

    NotificationCreate(String notificationId, boolean silent, NotificationItem item) {
      this.notificationId = notificationId;
      this.silent = silent;
      this.item = item;
    }
  }

  static final class NotificationItem {
    final String id;
    final String actorDisplay;
    final String roomId;
    final String threadRootId;
    final String kindLabel;

    NotificationItem(
        String id, String actorDisplay, String roomId, String threadRootId, String kindLabel) {
      this.id = id;
      this.actorDisplay = actorDisplay;
      this.roomId = roomId;
      this.threadRootId = threadRootId;
      this.kindLabel = kindLabel;
    }
  }

  /**
   * Collect {@code created_notification_id} values from a {@code RealtimeServerFrame}.
   * Unknown fields and non-create notification events are skipped.
   */
  static List<String> extractCreatedNotificationIds(byte[] frameBytes) {
    List<String> out = new ArrayList<>();
    Reader frame = new Reader(frameBytes);
    while (frame.hasMore()) {
      int tag = frame.readTag();
      int field = tag >>> 3;
      if (field == 1) {
        // RealtimeEvent
        collectCreatedIdsFromEvent(frame.readBytes(), out);
      } else if (field == 3) {
        // RealtimeClose — caller may reconnect; skip payload.
        frame.skipField(tag);
      } else {
        frame.skipField(tag);
      }
    }
    return out;
  }

  private static void collectCreatedIdsFromEvent(byte[] bytes, List<String> out) {
    Reader event = new Reader(bytes);
    while (event.hasMore()) {
      int tag = event.readTag();
      int field = tag >>> 3;
      if (field == 56) {
        // notification_occurrences_changed
        String id = createdNotificationId(event.readBytes());
        if (id != null && !id.isEmpty()) {
          out.add(id);
        }
      } else {
        event.skipField(tag);
      }
    }
  }

  private static String createdNotificationId(byte[] bytes) {
    Reader changed = new Reader(bytes);
    while (changed.hasMore()) {
      int tag = changed.readTag();
      int field = tag >>> 3;
      if (field == 1) {
        return changed.readString();
      }
      changed.skipField(tag);
    }
    return null;
  }

  /** True when the frame is a server-requested close. */
  static boolean isCloseFrame(byte[] frameBytes) {
    Reader frame = new Reader(frameBytes);
    while (frame.hasMore()) {
      int tag = frame.readTag();
      int field = tag >>> 3;
      if (field == 3) {
        return true;
      }
      frame.skipField(tag);
    }
    return false;
  }
}
