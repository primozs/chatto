import { isSafeInternalPath } from '$lib/navigation/safeInternalPath';

/** Segments in notification deep links: server / room / thread ids. */
const CHAT_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

/**
 * Normalize a native notification open target to a same-origin SPA chat path.
 *
 * Only `/chat/{serverId}`, `/chat/{serverId}/{roomId}`, and
 * `/chat/{serverId}/{roomId}/{threadRootId}` are accepted. Absolute URLs,
 * protocol-relative values, traversal (`..` / `.`), and other SPA routes are
 * rejected so a forged banner or intent cannot drive arbitrary navigation.
 */
export function resolveNotificationOpenPath(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  const pathOnly = trimmed.split(/[?#]/, 1)[0] ?? trimmed;
  if (!isSafeInternalPath(pathOnly)) return null;
  // Reject before URL normalization can collapse `/chat/x/../settings` → `/settings`.
  if (pathOnly.split('/').some((segment) => segment === '.' || segment === '..')) {
    return null;
  }
  try {
    const u = new URL(trimmed, 'https://chatto.invalid');
    if (u.search || u.hash) return null;
    if (u.pathname !== pathOnly) return null;
    if (!isAllowedChatPath(u.pathname)) return null;
    return u.pathname;
  } catch {
    return null;
  }
}

/** True when pathname is an allowlisted notification chat deep link. */
export function isAllowedChatPath(pathname: string): boolean {
  if (!pathname.startsWith('/chat/')) return false;
  const segments = pathname.slice('/chat/'.length).split('/');
  if (segments.length < 1 || segments.length > 3) return false;
  return segments.every((segment) => CHAT_SEGMENT.test(segment));
}
