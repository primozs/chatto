import { describe, expect, it } from 'vitest';
import { resolveNotificationOpenPath } from './notificationOpenPath';

describe('resolveNotificationOpenPath', () => {
  it('accepts root-relative chat paths', () => {
    expect(resolveNotificationOpenPath('/chat/remote')).toBe('/chat/remote');
    expect(resolveNotificationOpenPath('/chat/remote/room-1')).toBe('/chat/remote/room-1');
    expect(resolveNotificationOpenPath('/chat/remote/room-1/thread-2')).toBe(
      '/chat/remote/room-1/thread-2'
    );
  });

  it('rejects non-chat SPA routes and path traversal', () => {
    expect(resolveNotificationOpenPath('/settings')).toBeNull();
    expect(resolveNotificationOpenPath('/chat/remote/../settings')).toBeNull();
    expect(resolveNotificationOpenPath('/chat/../settings')).toBeNull();
    expect(resolveNotificationOpenPath('/chat/remote/./room')).toBeNull();
    expect(resolveNotificationOpenPath('/chat/remote/room/extra/too-deep')).toBeNull();
  });

  it('rejects protocol-relative and absolute open redirects', () => {
    expect(resolveNotificationOpenPath('//evil.example/phish')).toBeNull();
    expect(resolveNotificationOpenPath('https://evil.example/chat/x')).toBeNull();
    expect(resolveNotificationOpenPath('javascript:alert(1)')).toBeNull();
  });

  it('rejects backslash and control-character variants', () => {
    expect(resolveNotificationOpenPath('/\\evil.example')).toBeNull();
    expect(resolveNotificationOpenPath('/chat/\u0000room')).toBeNull();
  });

  it('rejects empty and non-string values', () => {
    expect(resolveNotificationOpenPath('')).toBeNull();
    expect(resolveNotificationOpenPath('   ')).toBeNull();
    expect(resolveNotificationOpenPath(null)).toBeNull();
  });
});
