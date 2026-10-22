import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminUnlock } from './adminUnlock';

afterEach(() => adminUnlock.clear());

describe('adminUnlock', () => {
  it('holds the admin token in memory only', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    adminUnlock.set({ admin_token: 'adm', expires_at: '2099-01-01T00:00:00Z' });
    expect(adminUnlock.getAdminToken()).toBe('adm');
    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('expires by expires_at', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-21T18:00:00Z'));
    adminUnlock.set({ admin_token: 'adm', expires_at: '2026-10-21T18:15:00Z' });
    vi.setSystemTime(new Date('2026-10-21T18:14:59Z'));
    expect(adminUnlock.getAdminToken()).toBe('adm');
    vi.setSystemTime(new Date('2026-10-21T18:15:00Z'));
    expect(adminUnlock.getAdminToken()).toBeNull();
  });

  it('clear() drops the token and notifies subscribers', () => {
    const listener = vi.fn();
    const unsubscribe = adminUnlock.subscribe(listener);
    adminUnlock.set({ admin_token: 'adm', expires_at: '2099-01-01T00:00:00Z' });
    adminUnlock.clear();
    expect(adminUnlock.getAdminToken()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
    adminUnlock.set({ admin_token: 'adm', expires_at: '2099-01-01T00:00:00Z' });
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('notifies subscribers when the token expires', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-21T18:00:00Z'));
    const listener = vi.fn();
    adminUnlock.subscribe(listener);
    adminUnlock.set({ admin_token: 'adm', expires_at: '2026-10-21T18:15:00Z' });
    listener.mockClear();
    vi.advanceTimersByTime(15 * 60 * 1000);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(adminUnlock.getAdminToken()).toBeNull();
  });
});
