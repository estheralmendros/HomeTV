// Every admin op goes through useAdminAction: PIN keypad when locked, and one
// re-prompt + retry when the op fails with 401 admin_locked.
import { useCallback, useSyncExternalStore } from 'react';
import { isApiError } from '@/api/errors';
import { adminUnlock } from './adminUnlock';
import { useRequestAdminUnlock } from './AdminPinProvider';

/** Rejection of an admin op whose PIN prompt was cancelled (or answered 403). */
export class AdminActionCancelled extends Error {
  constructor() {
    super('admin action cancelled');
    this.name = 'AdminActionCancelled';
  }
}

export function isAdminActionCancelled(err: unknown): err is AdminActionCancelled {
  return err instanceof AdminActionCancelled;
}

/** The admin token held in memory (re-renders on unlock, clear and expiry). */
export function useAdminToken(): string | null {
  return useSyncExternalStore(adminUnlock.subscribe, adminUnlock.getAdminToken, adminUnlock.getAdminToken);
}

export type RunAdminAction = <T>(op: () => Promise<T>, purpose?: string) => Promise<T>;

/**
 * `run(op, purpose?)`: runs `op` when an admin token is held, else opens the keypad first. If
 * `op` fails with `admin_locked` (the client already dropped the token) it re-prompts and
 * retries once. Rejects with AdminActionCancelled when the prompt is cancelled.
 */
export function useAdminAction(): RunAdminAction {
  const requestUnlock = useRequestAdminUnlock();
  return useCallback(
    async <T,>(op: () => Promise<T>, purpose?: string): Promise<T> => {
      const unlock = async () => {
        if (!(await requestUnlock(purpose))) throw new AdminActionCancelled();
      };
      if (adminUnlock.getAdminToken() === null) await unlock();
      try {
        return await op();
      } catch (err) {
        if (!isApiError(err, 'admin_locked')) throw err;
        adminUnlock.clear();
        await unlock();
        return op();
      }
    },
    [requestUnlock],
  );
}
