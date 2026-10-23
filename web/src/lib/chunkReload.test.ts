import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CHUNK_RELOAD_KEY, clearChunkReloadMarker, handlePreloadError, isChunkLoadError, registerChunkReload, reloadOnce } from './chunkReload';
import { reloader } from './reload';

let reload: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-22T10:00:00Z'));
  reload = vi.spyOn(reloader, 'reload').mockImplementation(() => undefined);
});
afterEach(() => vi.useRealTimers());

function throwingSessionStorage() {
  vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
    throw new DOMException('denied', 'SecurityError');
  });
}

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://x/assets/PlayerPage-abc.js',
    'Importing a module script failed.',
    'error loading dynamically imported module: https://x/assets/a.js',
    'Unable to preload CSS for /assets/AdminLayout-abc.css',
  ])('recognises %s', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true);
    expect(isChunkLoadError(new Error(message))).toBe(true);
  });

  it('recognises ChunkLoadError by name', () => {
    const err = new Error('Loading chunk 3 failed');
    err.name = 'ChunkLoadError';
    expect(isChunkLoadError(err)).toBe(true);
  });

  it('rejects other errors', () => {
    expect(isChunkLoadError(new TypeError('Cannot read properties of undefined'))).toBe(false);
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(false);
    expect(isChunkLoadError(null)).toBe(false);
  });
});

describe('reloadOnce', () => {
  it('reloads when the key is absent and records the time', () => {
    expect(reloadOnce()).toBe('reloading');
    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBe(String(Date.now()));
  });

  it('does not reload a second time within 10 s, without side effects', () => {
    expect(reloadOnce()).toBe('reloading');
    const stored = sessionStorage.getItem(CHUNK_RELOAD_KEY);
    vi.advanceTimersByTime(9_000);
    expect(reloadOnce()).toBe('gave_up');
    expect(reloadOnce()).toBe('gave_up');
    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBe(stored);
  });

  it('reloads again when the key is older than 10 s', () => {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now() - 10_001));
    expect(reloadOnce()).toBe('reloading');
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('never reloads when sessionStorage throws', () => {
    throwingSessionStorage();
    expect(reloadOnce()).toBe('gave_up');
    expect(reload).not.toHaveBeenCalled();
  });

  it('passes the target URL to the reloader', () => {
    expect(reloadOnce('/watch/film/2')).toBe('reloading');
    expect(reload).toHaveBeenCalledWith('/watch/film/2');
  });

  it('clearChunkReloadMarker removes the key', () => {
    reloadOnce();
    clearChunkReloadMarker();
    expect(sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBeNull();
  });
});

describe('vite:preloadError handler', () => {
  const preloadEvent = () => new Event('vite:preloadError', { cancelable: true });

  it('calls preventDefault() on the first failure only', () => {
    const unregister = registerChunkReload(window);
    const first = preloadEvent();
    window.dispatchEvent(first);
    expect(first.defaultPrevented).toBe(true);
    const second = preloadEvent();
    window.dispatchEvent(second);
    expect(second.defaultPrevented).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
    unregister();
  });

  it('reloads the pending navigation target when one is given, else the current page', () => {
    const unregister = registerChunkReload(window, () => '/watch/film/2');
    window.dispatchEvent(preloadEvent());
    expect(reload).toHaveBeenCalledWith('/watch/film/2');
    unregister();
    sessionStorage.removeItem(CHUNK_RELOAD_KEY);
    const plain = registerChunkReload(window, () => undefined);
    window.dispatchEvent(preloadEvent());
    expect(reload).toHaveBeenLastCalledWith(undefined);
    plain();
  });

  it('does not call preventDefault() when sessionStorage throws', () => {
    throwingSessionStorage();
    const event = preloadEvent();
    handlePreloadError(event);
    expect(event.defaultPrevented).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });
});
