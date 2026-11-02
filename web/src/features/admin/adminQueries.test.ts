import { describe, expect, it } from 'vitest';
import type { RunAdminAction } from '@/auth/useAdminAction';
import * as f from '../../../tests/fixtures';
import { browseQuery, SCAN_POLL_MS, scanRefetchInterval, tmdbSearchQuery, tmdbSeasonQuery, tmdbSeasonsQuery } from './adminQueries';

// The TMDB lookups are not gated by `enabled: token !== null` (they run through useAdminAction,
// see adminQueries.ts), so no background refetch may pop the PIN keypad unprompted.
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

describe('scan poll interval', () => {
  it.each([
    ['a running scan', f.scanStatus({ state: 'running' }), SCAN_POLL_MS],
    ['a finished scan while change detection is starting', f.scanStatus({ watcher: f.watcherStatus({ state: 'starting' }) }), SCAN_POLL_MS],
    ['an idle server while change detection is starting', f.idleScanStatus({ watcher: f.watcherStatus({ state: 'starting' }) }), SCAN_POLL_MS],
    ['a finished scan with change detection active', f.scanStatus(), false],
    ['a finished scan with change detection in error', f.scanStatus({ watcher: f.watcherStatus({ state: 'error' }) }), false],
    ['no data yet', undefined, false],
  ])('%s', (_name, data, interval) => {
    expect(scanRefetchInterval(data)).toBe(interval);
  });
});
