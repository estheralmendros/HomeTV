// Stale lazy chunks after a redeploy (prd2 §13): reload once, never loop.
import type { createBrowserRouter } from 'react-router-dom';
import { reloader } from './reload';

export const CHUNK_RELOAD_KEY = 'hometv.chunkReloadAt';
const RELOAD_WINDOW_MS = 10_000;
const CHUNK_ERROR =
  /dynamically imported module|Importing a module script failed|error loading dynamically imported module|Unable to preload CSS/i;

export type ReloadOutcome = 'reloading' | 'gave_up';

/** A failed dynamic import / preload (Chrome, Safari, Firefox wordings, Vite CSS preload). */
export function isChunkLoadError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  return err.name === 'ChunkLoadError' || CHUNK_ERROR.test(err.message);
}

/**
 * Reloads the page (or loads `href`, the URL a pending navigation was heading to) unless a reload
 * already happened in the last 10 s (or sessionStorage is unavailable). A second call within 10 s
 * returns 'gave_up' with no side effects.
 */
export function reloadOnce(href?: string): ReloadOutcome {
  try {
    const storage = window.sessionStorage;
    const last = Number(storage.getItem(CHUNK_RELOAD_KEY));
    const now = Date.now();
    if (Number.isFinite(last) && last > 0 && now - last <= RELOAD_WINDOW_MS) return 'gave_up';
    storage.setItem(CHUNK_RELOAD_KEY, String(now));
  } catch {
    return 'gave_up';
  }
  reloader.reload(href);
  return 'reloading';
}

/** The "app was updated" [Recargar] button: forget the marker, then reload. */
export function clearChunkReloadMarker(): void {
  try {
    window.sessionStorage.removeItem(CHUNK_RELOAD_KEY);
  } catch {
    // storage unavailable
  }
}

/**
 * `vite:preloadError`: preventDefault() (the failed import then resolves to undefined) only
 * when the page is actually reloading; otherwise the import rejects and reaches the error UI.
 * `href` is the pending navigation's target: a lazy route's chunk fails before the router
 * commits the new location, so a plain reload would reopen the page being left.
 */
export function handlePreloadError(event: Event, href?: string): void {
  if (reloadOnce(href) === 'reloading') event.preventDefault();
}

export function registerChunkReload(target: Window = window, pendingHref: () => string | undefined = () => undefined): () => void {
  const listener = (event: Event) => handlePreloadError(event, pendingHref());
  target.addEventListener('vite:preloadError', listener);
  return () => target.removeEventListener('vite:preloadError', listener);
}

type AppRouter = Pick<ReturnType<typeof createBrowserRouter>, 'state' | 'createHref'>;

/** The URL the router is navigating to (lazy route still loading), or undefined when idle. */
export function pendingNavigationHref(router: AppRouter): string | undefined {
  const { location } = router.state.navigation;
  return location ? router.createHref(location) : undefined;
}
