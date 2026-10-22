import { describe, expect, it, vi } from 'vitest';
import {
  LAST_PROFILE_KEY,
  SESSION_KEY,
  clearSessionIfToken,
  isExpired,
  parseSession,
  readLastProfileId,
  readSession,
  sessionFromResponse,
  writeLastProfileId,
  writeSession,
} from './session';

const valid = { token: 't1', profile_id: 1, expires_at: '2099-01-01T00:00:00Z' };

describe('session storage', () => {
  it('writes and reads hometv.session', () => {
    writeSession(valid);
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toEqual(valid);
    expect(readSession()).toEqual(valid);
  });

  it('rejects malformed values', () => {
    expect(parseSession(null)).toBeNull();
    expect(parseSession('{')).toBeNull();
    expect(parseSession(JSON.stringify({ token: 't', profile_id: '1', expires_at: 'x' }))).toBeNull();
    expect(parseSession(JSON.stringify(valid))).toEqual(valid);
  });

  it('clears the stored session only when it holds the given token', () => {
    writeSession(valid);
    clearSessionIfToken('other');
    expect(readSession()).toEqual(valid);
    clearSessionIfToken('t1');
    expect(readSession()).toBeNull();
  });

  it('isExpired compares expires_at with now', () => {
    expect(isExpired(valid, Date.parse('2098-12-31T23:59:59Z'))).toBe(false);
    expect(isExpired(valid, Date.parse('2099-01-01T00:00:00Z'))).toBe(true);
    expect(isExpired({ ...valid, expires_at: 'garbage' })).toBe(true);
  });

  it('builds a session from a SessionResponse', () => {
    expect(
      sessionFromResponse({
        token: 't2',
        expires_at: '2099-01-01T00:00:00Z',
        profile: { id: 2, name: 'Kids', avatar_id: 'avatar-04', is_admin: false, created_at: '2026-10-21T18:04:05Z' },
      }),
    ).toEqual({ token: 't2', profile_id: 2, expires_at: '2099-01-01T00:00:00Z' });
  });

  it('stores the last profile id', () => {
    expect(readLastProfileId()).toBeNull();
    writeLastProfileId(3);
    expect(localStorage.getItem(LAST_PROFILE_KEY)).toBe('3');
    expect(readLastProfileId()).toBe(3);
  });

  it('keeps working when storage throws', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(() => writeSession(valid)).not.toThrow();
    expect(readSession()).toBeNull();
  });
});
