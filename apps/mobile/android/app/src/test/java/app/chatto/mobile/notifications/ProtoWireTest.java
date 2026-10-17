package app.chatto.mobile.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
// assertFalse used by consumeOpenGate_isOneTime

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** Wire-format checks for realtime v4 subscribe + notification create extraction. */
public class ProtoWireTest {

  @Test
  public void encodeRealtimeSubscribe_includesProtocolVersionAndLiveOnly() {
    byte[] encoded = ProtoWire.encodeRealtimeSubscribe("cht_ATtoken");
    // field 1 (protocol_version=4), field 2 (bearer), field 4 (initial_state=1)
    assertTrue(encoded.length > 4);
    assertEquals(ProtoWire.PROTOCOL_VERSION, 4);
    assertEquals(ProtoWire.INITIAL_STATE_LIVE_ONLY, 1);

    // Round-trip: protocol version tag (field 1, wire 0) + varint 4
    assertEquals(0x08, encoded[0] & 0xff);
    assertEquals(4, encoded[1] & 0xff);
  }

  @Test
  public void encodeRealtimeSubscribe_omitsEmptyBearer() {
    byte[] withToken = ProtoWire.encodeRealtimeSubscribe("tok");
    byte[] without = ProtoWire.encodeRealtimeSubscribe("");
    assertTrue(withToken.length > without.length);
  }

  @Test
  public void extractCreatedNotificationIds_readsNestedCreateField() {
    // RealtimeServerFrame { event: RealtimeEvent {
    //   notification_occurrences_changed: { created_notification_id: "n-1" } } }
    byte[] changed = lengthDelimited(1, "n-1".getBytes(StandardCharsets.UTF_8));
    byte[] event = lengthDelimited(56, changed);
    byte[] frame = lengthDelimited(1, event);

    List<String> ids = ProtoWire.extractCreatedNotificationIds(frame);
    assertEquals(1, ids.size());
    assertEquals("n-1", ids.get(0));
  }

  @Test
  public void extractCreatedNotificationIds_skipsUnknownFields() {
    byte[] changed = lengthDelimited(1, "keep".getBytes(StandardCharsets.UTF_8));
    byte[] event = concat(lengthDelimited(1, new byte[] {0x01}), lengthDelimited(56, changed));
    byte[] frame = lengthDelimited(1, event);

    List<String> ids = ProtoWire.extractCreatedNotificationIds(frame);
    assertEquals(1, ids.size());
    assertEquals("keep", ids.get(0));
  }

  @Test
  public void isCloseFrame_detectsFieldThree() {
    byte[] close = lengthDelimited(3, new byte[] {0x08, 0x01});
    assertTrue(ProtoWire.isCloseFrame(close));
    assertFalse(ProtoWire.isCloseFrame(lengthDelimited(1, new byte[] {0x08, 0x01})));
  }

  @Test
  public void buildPath_buildsChatDeepLinks() {
    assertEquals("/chat/srv", ChattoNotificationService.buildPath("srv", null, null));
    assertEquals("/chat/srv/room", ChattoNotificationService.buildPath("srv", "room", null));
    assertEquals(
        "/chat/srv/room/thread", ChattoNotificationService.buildPath("srv", "room", "thread"));
  }

  @Test
  public void consumeOpenGate_isOneTime() {
    assertFalse(ChattoNotificationService.consumeOpenGate("missing"));
    assertFalse(ChattoNotificationService.consumeOpenGate(null));
    ChattoNotificationService.registerOpenGate("gate-1");
    assertTrue(ChattoNotificationService.consumeOpenGate("gate-1"));
    assertFalse(ChattoNotificationService.consumeOpenGate("gate-1"));
  }

  private static byte[] lengthDelimited(int fieldNumber, byte[] value) {
    byte[] tag = varint((fieldNumber << 3) | 2);
    byte[] len = varint(value.length);
    return concat(tag, len, value);
  }

  private static byte[] varint(long value) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    while (true) {
      if ((value & ~0x7fL) == 0) {
        out.write((int) value);
        return out.toByteArray();
      }
      out.write((int) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
  }

  private static byte[] concat(byte[]... parts) {
    int len = 0;
    for (byte[] part : parts) {
      len += part.length;
    }
    byte[] out = new byte[len];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, at, part.length);
      at += part.length;
    }
    return out;
  }
}
