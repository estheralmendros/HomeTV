// Admin queries (lazy admin chunk). `['admin', …]` queries are disabled while no admin token is
// held (prd2 § State & data fetching); a 401 admin_locked clears the token, which pauses them
// until RequireAdmin's keypad unlocks again, then they refetch.
import { keepPreviousData, queryOptions, useQuery } from '@tanstack/react-query';
import { browseFolders, getLibraries, getScan, listUnmatched, tmdbSearch, tmdbSeason, tmdbSeasons } from '@/api/admin';
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

/**
 * Poll interval: every 2 s while the run is `running`, and while change detection is `starting`
 * (so the card leaves "iniciando…" once the first arming walk ends).
 */
export const scanRefetchInterval = (data: ScanStatus | undefined): number | false =>
  data?.state === 'running' || data?.watcher.state === 'starting' ? SCAN_POLL_MS : false;

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

/** `GET /api/admin/libraries` (Bibliotecas): folders with live status and title counts. */
export function useLibrariesQuery() {
  const token = useAdminToken();
  return useQuery({
    queryKey: queryKeys.admin.libraries(),
    queryFn: ({ signal }) => getLibraries(signal),
    staleTime: staleTimes.adminLibraries,
    enabled: token !== null,
  });
}

export const browseQuery = (path: string | null) =>
  queryOptions({
    queryKey: queryKeys.admin.browse(path),
    queryFn: ({ signal }) => browseFolders(path, signal),
    staleTime: staleTimes.adminBrowse,
    // Errors must show at once: the browser offers its own way back (no silent retries).
    retry: false,
  });

/**
 * `GET /api/admin/libraries/browse?path=` for the folder browser; the caller passes `enabled`
 * (dialog open and `browse_root.available`), and the admin token gates it too.
 */
export function useBrowseQuery(path: string | null, { enabled }: { enabled: boolean }) {
  const token = useAdminToken();
  return useQuery({ ...browseQuery(path), enabled: enabled && token !== null });
}

// --- TMDB lookups of the fix-match dialog ---------------------------------------------------
// The dialog also opens from detail pages (no RequireAdmin around it), so these run through
// useAdminAction: a missing/expired admin token prompts the keypad instead of disabling them.
// No client retries: the server already retries TMDB, and errors must show (and fall back) at once.
// They are not gated by `enabled: token !== null` either, so no background refetch (window focus,
// reconnect) may run them: it could pop the PIN keypad unprompted after the token was cleared.

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
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

export const tmdbSeasonsQuery = (run: RunAdminAction, tmdbId: number) =>
  queryOptions({
    queryKey: queryKeys.admin.tmdbSeasons(tmdbId),
    queryFn: ({ signal }) => run(() => tmdbSeasons(tmdbId, signal), S.fixMatch.action),
    staleTime: staleTimes.adminTmdb,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

export const tmdbSeasonQuery = (run: RunAdminAction, tmdbId: number, seasonNumber: number) =>
  queryOptions({
    queryKey: queryKeys.admin.tmdbSeason(tmdbId, seasonNumber),
    queryFn: ({ signal }) => run(() => tmdbSeason(tmdbId, seasonNumber, signal), S.fixMatch.action),
    staleTime: staleTimes.adminTmdb,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
