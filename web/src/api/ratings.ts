// Ratings endpoints (ratings-per-user prd.md FR-12). Bearer only; writes answer 204.
import { apiFetch } from './client';
import { kindSegment } from './kind';
import type { ActivityEntry, Page, RatingKind } from './types';

/** The agreed page size of /activity (server default). */
export const ACTIVITY_PAGE_SIZE = 50;

/** Creates or replaces the requester's rating (½ … 5 in ½ steps). */
export const setRating = (kind: RatingKind, id: number, stars: number) =>
  apiFetch<void>(`/api/ratings/${kindSegment(kind)}/${id}`, { method: 'PUT', body: { stars } });

/** Removes the requester's rating (the server also stores a dismissal). */
export const removeRating = (kind: RatingKind, id: number) =>
  apiFetch<void>(`/api/ratings/${kindSegment(kind)}/${id}`, { method: 'DELETE' });

/** "Ahora no": the prompt is not offered again for this title. */
export const dismissRatingPrompt = (kind: RatingKind, id: number) =>
  apiFetch<void>(`/api/ratings/${kindSegment(kind)}/${id}/dismiss-prompt`, { method: 'POST' });

export const getActivity = (query: { page: number; page_size: number }, signal?: AbortSignal) =>
  apiFetch<Page<ActivityEntry>>('/api/activity', { query: { ...query }, signal });
