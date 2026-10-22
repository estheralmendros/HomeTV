// Admin endpoints: every call except unlock sends X-Admin-Token when one is held (`admin: true`).
import { apiFetch } from './client';
import type {
  FilmDetail,
  Page,
  Profile,
  ProfileCreateRequest,
  ProfileUpdateRequest,
  ResolveRequest,
  ResolveResponse,
  ScanStatus,
  ShowDetail,
  TmdbSearchResponse,
  TmdbSeasonResponse,
  TmdbSeasonsResponse,
  TmdbType,
  UnlockResponse,
  UnmatchedEntry,
  UnmatchedQuery,
} from './types';

export const UNMATCHED_PAGE_SIZE = 50;

/** Bearer = profile token of an admin profile; no X-Admin-Token. */
export const unlockAdmin = (pin: string) =>
  apiFetch<UnlockResponse>('/api/admin/unlock', { method: 'POST', body: { pin } });

export const getScan = (signal?: AbortSignal) => apiFetch<ScanStatus>('/api/admin/scan', { admin: true, signal });

export const startScan = () => apiFetch<ScanStatus>('/api/admin/scan', { method: 'POST', admin: true });

export const listUnmatched = (query: UnmatchedQuery, signal?: AbortSignal) =>
  apiFetch<Page<UnmatchedEntry>>('/api/admin/unmatched', {
    query: { page_size: UNMATCHED_PAGE_SIZE, ...query },
    admin: true,
    signal,
  });

export const tmdbSearch = (type: TmdbType, q: string, year: number | null | undefined, signal?: AbortSignal) =>
  apiFetch<TmdbSearchResponse>('/api/admin/tmdb/search', { query: { type, q, year }, admin: true, signal });

export const tmdbSeasons = (tmdbId: number, signal?: AbortSignal) =>
  apiFetch<TmdbSeasonsResponse>(`/api/admin/tmdb/tv/${tmdbId}/seasons`, { admin: true, signal });

export const tmdbSeason = (tmdbId: number, seasonNumber: number, signal?: AbortSignal) =>
  apiFetch<TmdbSeasonResponse>(`/api/admin/tmdb/tv/${tmdbId}/seasons/${seasonNumber}`, { admin: true, signal });

export const matchFilm = (id: number, tmdbId: number) =>
  apiFetch<FilmDetail>(`/api/admin/films/${id}/match`, { method: 'POST', body: { tmdb_id: tmdbId }, admin: true });

export const matchShow = (id: number, tmdbId: number) =>
  apiFetch<ShowDetail>(`/api/admin/shows/${id}/match`, { method: 'POST', body: { tmdb_id: tmdbId }, admin: true });

export const resolveUnmatched = (entryId: number, body: ResolveRequest) =>
  apiFetch<ResolveResponse>(`/api/admin/unmatched/${entryId}/resolve`, { method: 'POST', body, admin: true });

export const createProfile = (body: ProfileCreateRequest) =>
  apiFetch<Profile>('/api/admin/profiles', { method: 'POST', body, admin: true });

export const updateProfile = (id: number, body: ProfileUpdateRequest) =>
  apiFetch<Profile>(`/api/admin/profiles/${id}`, { method: 'PATCH', body, admin: true });

export const deleteProfile = (id: number) =>
  apiFetch<void>(`/api/admin/profiles/${id}`, { method: 'DELETE', admin: true });
