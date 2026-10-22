import { apiFetch } from './client';
import type {
  EpisodeDetail,
  FilmDetail,
  FilmSummary,
  GenresResponse,
  HomeResponse,
  LibraryQuery,
  LibraryType,
  Page,
  SearchResponse,
  SeasonDetail,
  ShowDetail,
  ShowSummary,
} from './types';

export const HOME_LIMIT = 20;
export const LIBRARY_PAGE_SIZE = 60;
export const SEARCH_LIMIT = 20;

export const getHome = (signal?: AbortSignal) =>
  apiFetch<HomeResponse>('/api/home', { query: { limit: HOME_LIMIT }, signal });

export const listFilms = (query: LibraryQuery, signal?: AbortSignal) =>
  apiFetch<Page<FilmSummary>>('/api/films', { query: { ...query }, signal });

export const listShows = (query: LibraryQuery, signal?: AbortSignal) =>
  apiFetch<Page<ShowSummary>>('/api/shows', { query: { ...query }, signal });

export const getFilm = (id: number, signal?: AbortSignal) => apiFetch<FilmDetail>(`/api/films/${id}`, { signal });

export const getShow = (id: number, signal?: AbortSignal) => apiFetch<ShowDetail>(`/api/shows/${id}`, { signal });

export const getSeason = (showId: number, seasonNumber: number, signal?: AbortSignal) =>
  apiFetch<SeasonDetail>(`/api/shows/${showId}/seasons/${seasonNumber}`, { signal });

export const getEpisode = (id: number, signal?: AbortSignal) =>
  apiFetch<EpisodeDetail>(`/api/episodes/${id}`, { signal });

export const getGenres = (type: LibraryType, signal?: AbortSignal) =>
  apiFetch<GenresResponse>('/api/genres', { query: { type }, signal });

export const search = (q: string, signal?: AbortSignal) =>
  apiFetch<SearchResponse>('/api/search', { query: { q, limit: SEARCH_LIMIT }, signal });
