// "Mi lista" endpoints (watchlist prd.md FR-8). Bearer only; writes answer 204.
import { apiFetch } from './client';
import type { Page, WatchlistEntry, WatchlistKind, WatchlistQuery } from './types';

/** The agreed page size of the list (server default), not the library's 60. */
export const WATCHLIST_PAGE_SIZE = 50;

const segment = (kind: WatchlistKind) => (kind === 'film' ? 'films' : 'shows');

export const getWatchlist = (query: WatchlistQuery, signal?: AbortSignal) =>
  apiFetch<Page<WatchlistEntry>>('/api/watchlist', { query: { ...query }, signal });

export const addToWatchlist = (kind: WatchlistKind, id: number) =>
  apiFetch<void>(`/api/watchlist/${segment(kind)}/${id}`, { method: 'PUT' });

export const removeFromWatchlist = (kind: WatchlistKind, id: number) =>
  apiFetch<void>(`/api/watchlist/${segment(kind)}/${id}`, { method: 'DELETE' });
