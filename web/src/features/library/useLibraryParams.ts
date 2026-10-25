// Library grid state lives in the URL (prd2 §5 step 1): q, sort, order, genre, year_from,
// year_to, watched. Defaults (sort=title, the sort's default order, no filters) are left out of the URL.
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { LibraryQuery, SortField, SortOrder } from '@/api/types';

export interface LibraryParams {
  q: string;
  sort: SortField;
  order: SortOrder;
  genre: number | null;
  yearFrom: number | null;
  yearTo: number | null;
  /** true: only watched, false: only not watched, null: all (prd.md §10 `watched`). */
  watched: boolean | null;
}

export const DEFAULT_ORDER: Readonly<Record<SortField, SortOrder>> = { title: 'asc', year: 'desc', added: 'desc' };

export const DEFAULT_LIBRARY_PARAMS: LibraryParams = {
  q: '',
  sort: 'title',
  order: 'asc',
  genre: null,
  yearFrom: null,
  yearTo: null,
  watched: null,
};

export const MAX_QUERY_LENGTH = 100;
export const MIN_YEAR = 1900;
export const MAX_YEAR = 2100;

const SORTS: readonly SortField[] = ['title', 'year', 'added'];
const ORDERS: readonly SortOrder[] = ['asc', 'desc'];

function parseWatched(raw: string | null): boolean | null {
  return raw === 'true' ? true : raw === 'false' ? false : null;
}

function parseInteger(raw: string | null, min: number, max: number): number | null {
  if (raw === null || !/^\d+$/.test(raw)) return null;
  const n = Number(raw);
  return n >= min && n <= max ? n : null;
}

/** A year typed by the user (1900–2100), else null. */
export function parseYear(raw: string | null): number | null {
  return parseInteger(raw?.trim() ?? null, MIN_YEAR, MAX_YEAR);
}

export function parseLibraryParams(search: URLSearchParams): LibraryParams {
  const rawSort = search.get('sort');
  const sort = SORTS.find((s) => s === rawSort) ?? 'title';
  const rawOrder = search.get('order');
  const order = ORDERS.find((o) => o === rawOrder) ?? DEFAULT_ORDER[sort];
  return {
    q: (search.get('q') ?? '').trim().slice(0, MAX_QUERY_LENGTH),
    sort,
    order,
    genre: parseInteger(search.get('genre'), 1, Number.MAX_SAFE_INTEGER),
    yearFrom: parseYear(search.get('year_from')),
    yearTo: parseYear(search.get('year_to')),
    watched: parseWatched(search.get('watched')),
  };
}

export function libraryParamsToSearch(p: LibraryParams): URLSearchParams {
  const search = new URLSearchParams();
  if (p.q) search.set('q', p.q);
  if (p.sort !== 'title') search.set('sort', p.sort);
  if (p.order !== DEFAULT_ORDER[p.sort]) search.set('order', p.order);
  if (p.genre !== null) search.set('genre', String(p.genre));
  if (p.yearFrom !== null) search.set('year_from', String(p.yearFrom));
  if (p.yearTo !== null) search.set('year_to', String(p.yearTo));
  if (p.watched !== null) search.set('watched', String(p.watched));
  return search;
}

export function isYearRangeInvalid(p: LibraryParams): boolean {
  return p.yearFrom !== null && p.yearTo !== null && p.yearFrom > p.yearTo;
}

/** Title, genre, year or watched filters (sort and order are not filters). */
export function hasFilters(p: LibraryParams): boolean {
  return p.q !== '' || p.genre !== null || p.yearFrom !== null || p.yearTo !== null || p.watched !== null;
}

/** GET /api/films|shows params without paging (also the infinite query's key part). */
export function toLibraryQuery(p: LibraryParams): Omit<LibraryQuery, 'page' | 'page_size'> {
  const query: Omit<LibraryQuery, 'page' | 'page_size'> = { sort: p.sort, order: p.order };
  if (p.q) query.q = p.q;
  if (p.genre !== null) query.genre = p.genre;
  if (p.yearFrom !== null) query.year_from = p.yearFrom;
  if (p.yearTo !== null) query.year_to = p.yearTo;
  if (p.watched !== null) query.watched = p.watched;
  return query;
}

export function useLibraryParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseLibraryParams(searchParams), [searchParams]);

  const update = useCallback(
    (patch: Partial<LibraryParams>) =>
      setSearchParams((prev) => libraryParamsToSearch({ ...parseLibraryParams(prev), ...patch }), { replace: true }),
    [setSearchParams],
  );
  const setSort = useCallback((sort: SortField) => update({ sort, order: DEFAULT_ORDER[sort] }), [update]);
  const toggleOrder = useCallback(() => update({ order: params.order === 'asc' ? 'desc' : 'asc' }), [params.order, update]);
  const clearFilters = useCallback(() => update({ q: '', genre: null, yearFrom: null, yearTo: null, watched: null }), [update]);

  return {
    params,
    update,
    setSort,
    toggleOrder,
    clearFilters,
    hasFilters: hasFilters(params),
    yearRangeInvalid: isYearRangeInvalid(params),
    query: useMemo(() => toLibraryQuery(params), [params]),
  };
}
