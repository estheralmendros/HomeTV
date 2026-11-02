// Query key factory, stale times and the invalidation table.
// Keys never include the profile id: switching profile clears the whole cache.
import type { QueryKey } from '@tanstack/react-query';
import { kindSegment } from './kind';
import type { LibraryQuery, LibraryType, RatingKind, TmdbType, UnmatchedQuery, WatchlistKind, WatchlistSort, WatchlistType } from './types';

export const queryKeys = {
  info: () => ['info'] as const,
  profiles: () => ['profiles'] as const,
  me: () => ['me'] as const,
  home: () => ['home'] as const,
  /** Infinite list; `params` excludes `page`. */
  library: (type: LibraryType, params: Omit<LibraryQuery, 'page' | 'page_size'>) => [type, 'list', params] as const,
  libraryAll: (type: LibraryType) => [type, 'list'] as const,
  genres: (type: LibraryType) => ['genres', type] as const,
  search: (q: string) => ['search', q] as const,
  searchAll: () => ['search'] as const,
  film: (id: number) => ['film', id] as const,
  show: (id: number) => ['show', id] as const,
  /** The film or show detail key for a title of either kind. */
  title: (kind: RatingKind | WatchlistKind, id: number) => (kind === 'film' ? queryKeys.film(id) : queryKeys.show(id)),
  season: (showId: number, seasonNumber: number) => ['season', showId, seasonNumber] as const,
  seasonsOf: (showId: number) => ['season', showId] as const,
  episode: (id: number) => ['episode', id] as const,
  person: (id: number) => ['person', id] as const,
  personAll: () => ['person'] as const,
  /** "Mi lista" infinite list; `params` excludes `page`/`page_size`. */
  watchlist: (params: { type: WatchlistType; sort: WatchlistSort; watched: boolean | null }) => ['watchlist', 'list', params] as const,
  watchlistAll: () => ['watchlist'] as const,
  /** /activity infinite list (always refetched on mount). */
  activity: () => ['activity', 'list'] as const,
  activityAll: () => ['activity'] as const,
  admin: {
    all: () => ['admin'] as const,
    scan: () => ['admin', 'scan'] as const,
    unmatched: (params: UnmatchedQuery) => ['admin', 'unmatched', params] as const,
    unmatchedAll: () => ['admin', 'unmatched'] as const,
    tmdbSearch: (type: TmdbType, q: string, year: number | null) => ['admin', 'tmdb', type, q, year] as const,
    tmdbSeasons: (tmdbId: number) => ['admin', 'tmdb', 'tv', tmdbId, 'seasons'] as const,
    tmdbSeason: (tmdbId: number, seasonNumber: number) => ['admin', 'tmdb', 'tv', tmdbId, 'season', seasonNumber] as const,
    libraries: () => ['admin', 'libraries'] as const,
    /** Folder browser listing; `null` = the browse root. A child of `libraries()` (prefix). */
    browse: (path: string | null) => ['admin', 'libraries', 'browse', path] as const,
  },
};

/** Mutation keys: a write in flight is visible to every component (and survives a remount). */
export const mutationKeys = {
  /** PUT/DELETE /api/watchlist/{films|shows}/{id}. */
  watchlistWrite: (kind: WatchlistKind, id: number) => ['watchlistWrite', kind, id] as const,
  /** PUT/DELETE /api/ratings/{films|shows}/{id}. */
  ratingWrite: (kind: RatingKind, id: number) => ['ratingWrite', kind, id] as const,
};

const SECOND = 1000;
const MINUTE = 60 * SECOND;

export const staleTimes = {
  info: Infinity,
  profiles: 30 * SECOND,
  me: 5 * MINUTE,
  home: 0,
  library: 30 * SECOND,
  genres: 5 * MINUTE,
  search: 30 * SECOND,
  film: 30 * SECOND,
  show: 30 * SECOND,
  season: 30 * SECOND,
  episode: 30 * SECOND,
  person: 30 * SECOND,
  watchlist: 30 * SECOND,
  activity: 0,
  adminScan: 0,
  adminUnmatched: 0,
  adminTmdb: 5 * MINUTE,
  adminLibraries: 0,
  adminBrowse: 0,
} as const;

export type PlayedItem =
  | { type: 'film'; id: number }
  | { type: 'episode'; id: number; showId: number; seasonNumber: number };

export type InvalidationEvent =
  /** Player exit / next episode (after the final report), or a mid-playback watched=true deferred to exit. */
  | { type: 'playerExit'; item: PlayedItem }
  /** POST /api/watched for a film. */
  | { type: 'watchedFilm'; id: number }
  /** POST /api/watched for an episode, season or show. */
  | { type: 'watchedEpisodes'; showId: number }
  /** Match / resolve success, scan finished. */
  | { type: 'catalogChanged' }
  /** PUT/DELETE /api/ratings/... settled (success or failure). Dismissing a prompt invalidates nothing. */
  | { type: 'ratingChanged'; kind: RatingKind; id: number }
  /** PUT/DELETE /api/watchlist/... settled (success or failure). */
  | { type: 'watchlistChanged'; kind: WatchlistKind; id: number }
  /** PUT /api/admin/libraries success (folders saved, a `libraries` scan may have started). */
  | { type: 'librariesChanged' }
  /** Profile create / edit / delete. */
  | { type: 'profilesChanged' };

/** The query keys (prefixes) to invalidate after an event (the invalidation table). */
export function invalidateAfter(event: InvalidationEvent): QueryKey[] {
  switch (event.type) {
    case 'playerExit': {
      const { item } = event;
      const itemKeys: QueryKey[] =
        item.type === 'film'
          ? [queryKeys.film(item.id)]
          : [queryKeys.episode(item.id), queryKeys.show(item.showId), queryKeys.season(item.showId, item.seasonNumber)];
      return [
        queryKeys.home(),
        ...itemKeys,
        queryKeys.libraryAll('films'),
        queryKeys.libraryAll('shows'),
        queryKeys.searchAll(),
        // Actor pages and "Mi lista" carry watch state on their cards.
        queryKeys.personAll(),
        queryKeys.watchlistAll(),
      ];
    }
    case 'watchedFilm':
      return [
        queryKeys.film(event.id), queryKeys.home(), queryKeys.libraryAll('films'), queryKeys.searchAll(), queryKeys.personAll(),
        queryKeys.watchlistAll(),
      ];
    case 'watchedEpisodes':
      return [
        queryKeys.show(event.showId),
        queryKeys.seasonsOf(event.showId),
        ['episode'],
        queryKeys.home(),
        queryKeys.libraryAll('shows'),
        queryKeys.searchAll(),
        queryKeys.personAll(),
        queryKeys.watchlistAll(),
      ];
    case 'catalogChanged':
      return [
        ['home'], ['films'], ['shows'], ['genres'], ['search'], ['film'], ['show'], ['season'], ['episode'],
        queryKeys.admin.unmatchedAll(), queryKeys.admin.scan(),
        // Title counts per folder change when a scan ends.
        queryKeys.admin.libraries(),
        queryKeys.personAll(),
        queryKeys.watchlistAll(),
        // A scan can hide or show rated titles.
        queryKeys.activityAll(),
      ];
    case 'ratingChanged':
      // Watch events never add ['activity']: watching does not change a rating, and /activity
      // refetches on every mount anyway.
      return [
        queryKeys.title(event.kind, event.id),
        queryKeys.activityAll(),
        queryKeys.libraryAll(kindSegment(event.kind)),
        queryKeys.home(),
        queryKeys.searchAll(),
        queryKeys.personAll(),
        queryKeys.watchlistAll(),
      ];
    case 'watchlistChanged':
      return [queryKeys.title(event.kind, event.id), queryKeys.watchlistAll()];
    case 'librariesChanged':
      return [queryKeys.admin.libraries(), queryKeys.admin.scan()];
    case 'profilesChanged':
      return [queryKeys.profiles(), queryKeys.me()];
  }
}
