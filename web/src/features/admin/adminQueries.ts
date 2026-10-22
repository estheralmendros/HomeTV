// Admin queries (lazy admin chunk). `['admin', …]` queries are disabled while no admin token is
// held (prd2 § State & data fetching); a 401 admin_locked clears the token, which pauses them
// until RequireAdmin's keypad unlocks again, then they refetch.
import { keepPreviousData, queryOptions, useQuery } from '@tanstack/react-query';
import { getScan, listUnmatched, tmdbSearch, tmdbSeason, tmdbSeasons } from '@/api/admin';
import { queryKeys, staleTimes } from '@/api/queryKeys';
import type { ScanStatus, TmdbType, UnmatchedQuery } from '@/api/types';
import { useAdminToken, type RunAdminAction } from '@/auth/useAdminAction';
import { S } from '@/strings.es';

export const SCAN_POLL_MS = 2000;

export const scanQuery = () =>
  queryOptions({
    queryKey: queryKeys.admin.scan(),
    queryFn: ({ signal }) => getScan(signal),
    staleTime: staleTimes.adminScan,
  });

/** Poll interval: every 2 s while the run is `running`. */
export const scanRefetchInterval = (data: ScanStatus | undefined): number | false =>
  data?.state === 'running' ? SCAN_POLL_MS : false;

/**
 * `GET /api/admin/scan`. Only AdminLayout polls (`poll: true`, one interval for the whole admin
 * section, so the badge and the finish detection keep working on every tab).
 */
export function useScanQuery({ poll = false }: { poll?: boolean } = {}) {
  const token = useAdminToken();
  return useQuery({
    ...scanQuery(),
    enabled: token !== null,
    refetchInterval: poll ? (query) => scanRefetchInterval(query.state.data) : false,
  });
}

export function useUnmatchedQuery(params: UnmatchedQuery) {
  const token = useAdminToken();
  return useQuery({
    queryKey: queryKeys.admin.unmatched(params),
    queryFn: ({ signal }) => listUnmatched(params, signal),
    staleTime: staleTimes.adminUnmatched,
    enabled: token !== null,
    placeholderData: keepPreviousData,
  });
}

// --- TMDB lookups of the fix-match dialog ---------------------------------------------------
// The dialog also opens from detail pages (no RequireAdmin around it), so these run through
// useAdminAction: a missing/expired admin token prompts the keypad instead of disabling them.
// No client retries: the server already retries TMDB, and errors must show (and fall back) at once.

export interface TmdbSearchParams {
  type: TmdbType;
  q: string;
  year: number | null;
}

export const tmdbSearchQuery = (run: RunAdminAction, params: TmdbSearchParams) =>
  queryOptions({
    queryKey: queryKeys.admin.tmdbSearch(params.type, params.q, params.year),
    queryFn: ({ signal }) => run(() => tmdbSearch(params.type, params.q, params.year, signal), S.fixMatch.action),
    staleTime: staleTimes.adminTmdb,
    retry: false,
  });

export const tmdbSeasonsQuery = (run: RunAdminAction, tmdbId: number) =>
  queryOptions({
    queryKey: queryKeys.admin.tmdbSeasons(tmdbId),
    queryFn: ({ signal }) => run(() => tmdbSeasons(tmdbId, signal), S.fixMatch.action),
    staleTime: staleTimes.adminTmdb,
    retry: false,
  });

export const tmdbSeasonQuery = (run: RunAdminAction, tmdbId: number, seasonNumber: number) =>
  queryOptions({
    queryKey: queryKeys.admin.tmdbSeason(tmdbId, seasonNumber),
    queryFn: ({ signal }) => run(() => tmdbSeason(tmdbId, seasonNumber, signal), S.fixMatch.action),
    staleTime: staleTimes.adminTmdb,
    retry: false,
  });
