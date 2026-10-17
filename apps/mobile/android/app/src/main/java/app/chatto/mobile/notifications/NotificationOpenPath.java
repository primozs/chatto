package app.chatto.mobile.notifications;

import java.util.regex.Pattern;

/**
 * Allowlisted SPA paths for notification taps. Mirrors frontend
 * {@code resolveNotificationOpenPath} / {@code isAllowedChatPath}.
 */
final class NotificationOpenPath {
  private static final Pattern SEGMENT =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

  private NotificationOpenPath() {}

  /** True when {@code pathname} is an allowlisted `/chat/...` deep link. */
  static boolean isAllowed(String pathname) {
    if (pathname == null || !pathname.startsWith("/chat/")) {
      return false;
    }
    if (pathname.indexOf('?') >= 0 || pathname.indexOf('#') >= 0) {
      return false;
    }
    String rest = pathname.substring("/chat/".length());
    if (rest.isEmpty()) {
      return false;
    }
    String[] segments = rest.split("/", -1);
    if (segments.length < 1 || segments.length > 3) {
      return false;
    }
    for (String segment : segments) {
      if (!SEGMENT.matcher(segment).matches()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Build an allowlisted chat path from ids. Unsafe segments are replaced with
   * {@code -} (server) or omitted (room/thread) so banners never carry forged routes.
   */
  static String buildPath(String serverId, String roomId, String threadRootId) {
    String server = sanitizeSegment(serverId, "-");
    String room = sanitizeSegment(roomId, null);
    if (room == null) {
      return "/chat/" + server;
    }
    String thread = sanitizeSegment(threadRootId, null);
    if (thread == null) {
      return "/chat/" + server + "/" + room;
    }
    return "/chat/" + server + "/" + room + "/" + thread;
  }

  private static String sanitizeSegment(String raw, String emptyFallback) {
    if (raw == null || raw.isEmpty()) {
      return emptyFallback;
    }
    // Reject rather than rewrite — stripping could map hostile ids onto real ones.
    if (SEGMENT.matcher(raw).matches()) {
      return raw;
    }
    return emptyFallback;
  }
}
