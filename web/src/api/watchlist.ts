// "Mi lista" endpoints. Bearer only; writes answer 204.
import { apiFetch } from './client';
import { kindSegment } from './kind';
import type { Page, WatchlistEntry, WatchlistKind, WatchlistQuery } from './types';

/** The agreed page size of the list (server default), not the library's 60. */
export const WATCHLIST_PAGE_SIZE = 50;

export const getWatchlist = (query: WatchlistQuery, signal?: AbortSignal) =>
  apiFetch<Page<WatchlistEntry>>('/api/watchlist', { query: { ...query }, signal });

export const addToWatchlist = (kind: WatchlistKind, id: number) =>
  apiFetch<void>(`/api/watchlist/${kindSegment(kind)}/${id}`, { method: 'PUT' });

export const removeFromWatchlist = (kind: WatchlistKind, id: number) =>
  apiFetch<void>(`/api/watchlist/${kindSegment(kind)}/${id}`, { method: 'DELETE' });
