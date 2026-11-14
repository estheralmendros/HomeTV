import { describe, expect, it } from 'vitest';
import { ApiError } from './errors';
import { createQueryClient, shouldRetry } from './queryClient';
import { invalidateAfter, mutationKeys, queryKeys, staleTimes } from './queryKeys';

describe('queryKeys', () => {
  it('builds the documented keys', () => {
    expect(queryKeys.info()).toEqual(['info']);
    expect(queryKeys.profiles()).toEqual(['profiles']);
    expect(queryKeys.me()).toEqual(['me']);
    expect(queryKeys.home()).toEqual(['home']);
    expect(queryKeys.library('films', { sort: 'title', order: 'asc' })).toEqual(['films', 'list', { sort: 'title', order: 'asc' }]);
    expect(queryKeys.libraryAll('shows')).toEqual(['shows', 'list']);
    expect(queryKeys.genres('films')).toEqual(['genres', 'films']);
    expect(queryKeys.search('dune')).toEqual(['search', 'dune']);
    expect(queryKeys.film(12)).toEqual(['film', 12]);
    expect(queryKeys.show(7)).toEqual(['show', 7]);
    expect(queryKeys.title('film', 12)).toEqual(queryKeys.film(12));
    expect(queryKeys.title('show', 7)).toEqual(queryKeys.show(7));
    expect(queryKeys.season(7, 2)).toEqual(['season', 7, 2]);
    expect(queryKeys.seasonsOf(7)).toEqual(['season', 7]);
    expect(queryKeys.episode(345)).toEqual(['episode', 345]);
    expect(queryKeys.person(3)).toEqual(['person', 3]);
    expect(queryKeys.personAll()).toEqual(['person']);
    expect(queryKeys.watchlist({ type: 'all', sort: 'added', watched: null })).toEqual([
      'watchlist',
      'list',
      { type: 'all', sort: 'added', watched: null },
    ]);
    expect(queryKeys.watchlist({ type: 'films', sort: 'title', watched: false })).toEqual([
      'watchlist',
      'list',
      { type: 'films', sort: 'title', watched: false },
    ]);
    expect(queryKeys.watchlistAll()).toEqual(['watchlist']);
    expect(queryKeys.libraryHomeRow('films')).toEqual(['films', 'list', 'homeRow']);
    expect(queryKeys.libraryHomeRow('shows')).toEqual(['shows', 'list', 'homeRow']);
    expect(queryKeys.watchlistHomeRow()).toEqual(['watchlist', 'homeRow']);
    expect(queryKeys.activity()).toEqual(['activity', 'list']);
    expect(queryKeys.activityAll()).toEqual(['activity']);
    expect(queryKeys.admin.scan()).toEqual(['admin', 'scan']);
    expect(queryKeys.admin.unmatched({ library: 'films', page: 2 })).toEqual(['admin', 'unmatched', { library: 'films', page: 2 }]);
    expect(queryKeys.admin.unmatchedAll()).toEqual(['admin', 'unmatched']);
    expect(queryKeys.admin.tmdbSearch('movie', 'dune', 2021)).toEqual(['admin', 'tmdb', 'movie', 'dune', 2021]);
    expect(queryKeys.admin.tmdbSeasons(2316)).toEqual(['admin', 'tmdb', 'tv', 2316, 'seasons']);
    expect(queryKeys.admin.tmdbSeason(2316, 2)).toEqual(['admin', 'tmdb', 'tv', 2316, 'season', 2]);
    expect(queryKeys.admin.libraries()).toEqual(['admin', 'libraries']);
    expect(queryKeys.admin.browse(null)).toEqual(['admin', 'libraries', 'browse', null]);
    expect(queryKeys.admin.browse('/media/Disco2')).toEqual(['admin', 'libraries', 'browse', '/media/Disco2']);
  });

  it('builds the mutation keys', () => {
    expect(mutationKeys.watchlistWrite('film', 12)).toEqual(['watchlistWrite', 'film', 12]);
    expect(mutationKeys.watchlistWrite('show', 7)).toEqual(['watchlistWrite', 'show', 7]);
    expect(mutationKeys.ratingWrite('film', 12)).toEqual(['ratingWrite', 'film', 12]);
    expect(mutationKeys.ratingWrite('show', 7)).toEqual(['ratingWrite', 'show', 7]);
  });

  it('has the documented stale times', () => {
    expect(staleTimes).toEqual({
      info: Infinity, profiles: 30_000, me: 300_000, home: 0, library: 30_000, genres: 300_000, search: 30_000,
      film: 30_000, show: 30_000, season: 30_000, episode: 30_000, person: 30_000, watchlist: 30_000, activity: 0, adminScan: 0, adminUnmatched: 0, adminTmdb: 300_000,
      adminLibraries: 0, adminBrowse: 0,
    });
  });
});

describe('invalidateAfter', () => {
  const lists = [['films', 'list'], ['shows', 'list'], ['search']];

  it('player exit of a film', () => {
    expect(invalidateAfter({ type: 'playerExit', item: { type: 'film', id: 12 } })).toEqual([
      ['home'], ['film', 12], ...lists, ['person'], ['watchlist'],
    ]);
  });

  it('player exit of an episode (detail + show + season)', () => {
    expect(
      invalidateAfter({ type: 'playerExit', item: { type: 'episode', id: 345, showId: 7, seasonNumber: 2 } }),
    ).toEqual([['home'], ['episode', 345], ['show', 7], ['season', 7, 2], ...lists, ['person'], ['watchlist']]);
  });

  it('watched film', () => {
    expect(invalidateAfter({ type: 'watchedFilm', id: 12 })).toEqual([
      ['film', 12], ['home'], ['films', 'list'], ['search'], ['person'], ['watchlist'],
    ]);
  });

  it('watched episode / season / show', () => {
    expect(invalidateAfter({ type: 'watchedEpisodes', showId: 7 })).toEqual([
      ['show', 7], ['season', 7], ['episode'], ['home'], ['shows', 'list'], ['search'], ['person'], ['watchlist'],
    ]);
  });

  it('match / resolve success and scan finished', () => {
    const keys = [
      ['home'], ['films'], ['shows'], ['genres'], ['search'], ['film'], ['show'], ['season'], ['episode'],
      ['admin', 'unmatched'], ['admin', 'scan'], ['admin', 'libraries'], ['person'], ['watchlist'], ['activity'],
    ];
    expect(invalidateAfter({ type: 'catalogChanged' })).toEqual(keys);
  });

  it('watchlist toggled: the detail and every watchlist list', () => {
    expect(invalidateAfter({ type: 'watchlistChanged', kind: 'film', id: 12 })).toEqual([['film', 12], ['watchlist']]);
    expect(invalidateAfter({ type: 'watchlistChanged', kind: 'show', id: 7 })).toEqual([['show', 7], ['watchlist']]);
  });

  it('rating changed: the detail, activity and every list whose cards show my_rating', () => {
    expect(invalidateAfter({ type: 'ratingChanged', kind: 'film', id: 12 })).toEqual([
      ['film', 12], ['activity'], ['films', 'list'], ['home'], ['search'], ['person'], ['watchlist'],
    ]);
    expect(invalidateAfter({ type: 'ratingChanged', kind: 'show', id: 7 })).toEqual([
      ['show', 7], ['activity'], ['shows', 'list'], ['home'], ['search'], ['person'], ['watchlist'],
    ]);
  });

  it('library folders saved: the libraries (and browse, by prefix) and the scan status', () => {
    expect(invalidateAfter({ type: 'librariesChanged' })).toEqual([['admin', 'libraries'], ['admin', 'scan']]);
  });

  it('track choice changed: the film detail; for a show every episode detail and Home (never the show)', () => {
    expect(invalidateAfter({ type: 'trackChoiceChanged', kind: 'film', id: 12 })).toEqual([['film', 12]]);
    expect(invalidateAfter({ type: 'trackChoiceChanged', kind: 'show', id: 7 })).toEqual([['episode'], ['home']]);
  });

  it('profile create / edit / delete', () => {
    expect(invalidateAfter({ type: 'profilesChanged' })).toEqual([['profiles'], ['me']]);
  });
});

describe('Home row keys are covered by the invalidation table', () => {
  const isPrefix = (prefix: readonly unknown[], key: readonly unknown[]) =>
    prefix.length <= key.length && prefix.every((segment, i) => JSON.stringify(segment) === JSON.stringify(key[i]));
  const covers = (event: Parameters<typeof invalidateAfter>[0], key: readonly unknown[]) =>
    invalidateAfter(event).some((prefix) => isPrefix(prefix, key));
  const films = queryKeys.libraryHomeRow('films');
  const shows = queryKeys.libraryHomeRow('shows');
  const list = queryKeys.watchlistHomeRow();

  it.each([
    ['watchlistChanged', { type: 'watchlistChanged', kind: 'film', id: 12 }, [list], [films, shows]],
    ['ratingChanged film', { type: 'ratingChanged', kind: 'film', id: 12 }, [films, list], [shows]],
    ['ratingChanged show', { type: 'ratingChanged', kind: 'show', id: 7 }, [shows, list], [films]],
    ['playerExit', { type: 'playerExit', item: { type: 'film', id: 12 } }, [films, shows, list], []],
    ['watchedFilm', { type: 'watchedFilm', id: 12 }, [films, list], [shows]],
    ['watchedEpisodes', { type: 'watchedEpisodes', showId: 7 }, [shows, list], [films]],
    ['catalogChanged', { type: 'catalogChanged' }, [films, shows, list], []],
  ] as const)('%s', (_name, event, covered, notCovered) => {
    for (const key of covered) expect(covers(event, key)).toBe(true);
    for (const key of notCovered) expect(covers(event, key)).toBe(false);
  });
});

describe('QueryClient defaults', () => {
  it('retries only network errors and 5xx, at most twice', () => {
    expect(shouldRetry(0, new ApiError({ status: 0, code: 'network_error' }))).toBe(true);
    expect(shouldRetry(1, new ApiError({ status: 502, code: 'tmdb_unavailable' }))).toBe(true);
    expect(shouldRetry(2, new ApiError({ status: 500, code: 'x' }))).toBe(false);
    expect(shouldRetry(0, new ApiError({ status: 404, code: 'not_found' }))).toBe(false);
    expect(shouldRetry(0, new TypeError('x'))).toBe(false);
  });

  it('sets staleTime 30 s, gcTime 5 min, no refetch on focus', () => {
    const defaults = createQueryClient().getDefaultOptions().queries;
    expect(defaults).toMatchObject({ staleTime: 30_000, gcTime: 300_000, refetchOnWindowFocus: false });
  });
});
