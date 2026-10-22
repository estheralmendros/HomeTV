// In-memory admin token (prd2 §2): never persisted, so a reload or a profile switch locks
// again. Valid until its server-issued `expires_at` (fixed 15 min, never extended here).
import type { UnlockResponse } from '@/api/types';

type Listener = () => void;

let current: { token: string; expiresAt: number } | null = null;
let expiryTimer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<Listener>();

function notify() {
  for (const listener of listeners) listener();
}

function clearTimer() {
  if (expiryTimer !== null) clearTimeout(expiryTimer);
  expiryTimer = null;
}

export const adminUnlock = {
  set(response: UnlockResponse): void {
    clearTimer();
    const expiresAt = Date.parse(response.expires_at);
    current = { token: response.admin_token, expiresAt };
    const ms = expiresAt - Date.now();
    if (Number.isFinite(ms)) {
      // Clamp to the setTimeout range; far-future tokens simply never fire.
      if (ms <= 2_147_483_647) {
        expiryTimer = setTimeout(() => {
          expiryTimer = null;
          current = null;
          notify();
        }, Math.max(0, ms));
      }
    }
    notify();
  },

  /** The token, or null when none is held or it has expired. */
  getAdminToken(): string | null {
    if (!current) return null;
    if (Date.now() >= current.expiresAt) return null;
    return current.token;
  },

  clear(): void {
    clearTimer();
    const had = current !== null;
    current = null;
    if (had) notify();
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
