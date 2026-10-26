import { describe, expect, it } from 'vitest';
import type { RunAdminAction } from '@/auth/useAdminAction';
import { browseQuery, tmdbSearchQuery, tmdbSeasonQuery, tmdbSeasonsQuery } from './adminQueries';

// The TMDB lookups are not gated by `enabled: token !== null` (they run through useAdminAction,
// prd2 § State & data fetching), so no background refetch may pop the PIN keypad unprompted.
describe('TMDB lookup queries', () => {
  const run = (() => Promise.reject(new Error('not called'))) as unknown as RunAdminAction;

  it.each([
    ['search', tmdbSearchQuery(run, { type: 'movie', q: 'dune', year: null })],
    ['seasons', tmdbSeasonsQuery(run, 438631)],
    ['season', tmdbSeasonQuery(run, 438631, 1)],
  ])('%s: no window-focus or reconnect refetch, no retries', (_name, options) => {
    expect(options).toMatchObject({ refetchOnWindowFocus: false, refetchOnReconnect: false, retry: false });
  });
});

describe('folder browser query', () => {
  it('keys by path and never retries (errors show at once with a way back)', () => {
    expect(browseQuery(null)).toMatchObject({ queryKey: ['admin', 'libraries', 'browse', null], retry: false, staleTime: 0 });
    expect(browseQuery('/media/A').queryKey).toEqual(['admin', 'libraries', 'browse', '/media/A']);
  });
});
