// "Mi lista" state lives in the URL (watchlist prd2 FR-W8): ?type=films|shows&watched=true|false&
// sort=title|year. Defaults ('all', null, 'added') and invalid values are never written; setters
// replace the history entry.
import { useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { WatchlistQuery, WatchlistSort, WatchlistType } from '@/api/types';

export interface WatchlistParams {
  type: WatchlistType;
  sort: WatchlistSort;
  /** true: only watched, false: only not watched, null: all (the library rule). */
  watched: boolean | null;
}

const TYPES: readonly WatchlistType[] = ['films', 'shows'];
const SORTS: readonly WatchlistSort[] = ['title', 'year'];

export function parseWatchlistParams(search: URLSearchParams): WatchlistParams {
  const rawType = search.get('type');
  const rawSort = search.get('sort');
  return {
    type: TYPES.find((t) => t === rawType) ?? 'all',
    sort: SORTS.find((s) => s === rawSort) ?? 'added',
    watched: parseWatched(search.get('watched')),
  };
}

function parseWatched(raw: string | null): boolean | null {
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  return null;
}

export function watchlistParamsToSearch(p: WatchlistParams): URLSearchParams {
  const search = new URLSearchParams();
  if (p.type !== 'all') search.set('type', p.type);
  if (p.watched !== null) search.set('watched', String(p.watched));
  if (p.sort !== 'added') search.set('sort', p.sort);
  return search;
}

/** GET /api/watchlist params without paging; defaults are `undefined` so they are not sent. */
export function toWatchlistQuery(p: WatchlistParams): Pick<WatchlistQuery, 'type' | 'sort' | 'watched'> {
  return {
    type: p.type === 'all' ? undefined : p.type,
    sort: p.sort === 'added' ? undefined : p.sort,
    watched: p.watched ?? undefined,
  };
}

export function useWatchlistParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseWatchlistParams(searchParams), [searchParams]);
  // setSearchParams' `prev` is the searchParams of the last render, not the URL: a second change
  // made before the first navigation renders (it runs in a transition) would bring back the old
  // value. The latest requested params build the next URL. They belong to the URL they were based
  // on: once a new URL commits (Back and NavBar links included), `update` starts again from it, a
  // re-render with the same URL does not.
  const committed = useRef(searchParams);
  useLayoutEffect(() => {
    committed.current = searchParams;
  }, [searchParams]);
  const latest = useRef<{ search: URLSearchParams; params: WatchlistParams } | null>(null);

  const update = useCallback(
    (patch: Partial<WatchlistParams>) => {
      const search = committed.current;
      const base = latest.current?.search === search ? latest.current.params : parseWatchlistParams(search);
      const next = { ...base, ...patch };
      latest.current = { search, params: next };
      setSearchParams(watchlistParamsToSearch(next), { replace: true });
    },
    [setSearchParams],
  );
  const setType = useCallback((type: WatchlistType) => update({ type }), [update]);
  const setSort = useCallback((sort: WatchlistSort) => update({ sort }), [update]);
  const setWatched = useCallback((watched: boolean | null) => update({ watched }), [update]);
  const showAll = useCallback(() => update({ type: 'all', watched: null }), [update]);

  return { params, setType, setSort, setWatched, showAll, query: useMemo(() => toWatchlistQuery(params), [params]) };
}
