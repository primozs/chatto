package app.chatto.mobile.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationOpenPathTest {

  @Test
  public void isAllowed_acceptsChatDeepLinks() {
    assertTrue(NotificationOpenPath.isAllowed("/chat/srv"));
    assertTrue(NotificationOpenPath.isAllowed("/chat/srv/room-1"));
    assertTrue(NotificationOpenPath.isAllowed("/chat/srv/room-1/thread_2"));
  }

  @Test
  public void isAllowed_rejectsNonChatAndTraversal() {
    assertFalse(NotificationOpenPath.isAllowed("/settings"));
    assertFalse(NotificationOpenPath.isAllowed("/chat/../settings"));
    assertFalse(NotificationOpenPath.isAllowed("/chat/srv/../settings"));
    assertFalse(NotificationOpenPath.isAllowed("/chat/srv/./room"));
    assertFalse(NotificationOpenPath.isAllowed("/chat/srv/room/extra/too-deep"));
    assertFalse(NotificationOpenPath.isAllowed("/chat/srv/room?x=1"));
  }

  @Test
  public void buildPath_sanitizesUnsafeSegments() {
    assertEquals("/chat/srv", NotificationOpenPath.buildPath("srv", null, null));
    assertEquals("/chat/-", NotificationOpenPath.buildPath("../evil", null, null));
    assertEquals("/chat/srv", NotificationOpenPath.buildPath("srv", "a/b", null));
    assertEquals(
        "/chat/srv/room/thread", NotificationOpenPath.buildPath("srv", "room", "thread"));
  }
}
