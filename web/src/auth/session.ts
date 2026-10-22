// localStorage read/write/clear of the session (prd2 §2 Session storage). Only the picker and
// bootstrap write it; AuthProvider reads it once at boot into its per-tab memory.
import type { SessionResponse } from '@/api/types';
import { readJson, readString, removeKey, writeJson, writeString } from '@/lib/storage';

export const SESSION_KEY = 'hometv.session';
export const LAST_PROFILE_KEY = 'hometv.lastProfileId';

export interface StoredSession {
  token: string;
  profile_id: number;
  expires_at: string;
}

function isStoredSession(value: unknown): value is StoredSession {
  const v = value as Partial<StoredSession> | null;
  return (
    !!v &&
    typeof v.token === 'string' &&
    v.token !== '' &&
    typeof v.profile_id === 'number' &&
    typeof v.expires_at === 'string'
  );
}

/** Parses a raw `hometv.session` value (also the `newValue` of a storage event). */
export function parseSession(raw: string | null): StoredSession | null {
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return isStoredSession(value) ? { token: value.token, profile_id: value.profile_id, expires_at: value.expires_at } : null;
  } catch {
    return null;
  }
}

export function readSession(): StoredSession | null {
  const value = readJson(SESSION_KEY);
  return isStoredSession(value) ? value : null;
}

export function writeSession(session: StoredSession): void {
  writeJson(SESSION_KEY, session);
}

/** Removes the stored session only if it still holds `token` (a newer tab's session survives). */
export function clearSessionIfToken(token: string): void {
  if (readSession()?.token === token) removeKey(SESSION_KEY);
}

export function isExpired(session: StoredSession, now: number = Date.now()): boolean {
  const expiresAt = Date.parse(session.expires_at);
  return !Number.isFinite(expiresAt) || expiresAt <= now;
}

export function sessionFromResponse(response: SessionResponse): StoredSession {
  return { token: response.token, profile_id: response.profile.id, expires_at: response.expires_at };
}

export function readLastProfileId(): number | null {
  const raw = readString(LAST_PROFILE_KEY);
  const id = raw === null ? Number.NaN : Number(raw);
  return Number.isInteger(id) ? id : null;
}

export function writeLastProfileId(id: number): void {
  writeString(LAST_PROFILE_KEY, String(id));
}
