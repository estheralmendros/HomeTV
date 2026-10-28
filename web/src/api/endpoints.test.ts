// Every endpoint function hits the prd.md method + path with the right query/body.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { recordRequests } from '../../tests/msw/server';
import { adminUnlock } from '@/auth/adminUnlock';
import * as admin from './admin';
import * as catalog from './catalog';
import { setTokenGetter } from './client';
import * as people from './people';
import * as playback from './playback';
import * as profiles from './profiles';
import * as system from './system';
import * as watch from './watch';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
  setTokenGetter(() => 'tok');
  adminUnlock.set({ admin_token: 'adm', expires_at: '2099-01-01T00:00:00Z' });
});
afterEach(() => rec.dispose());

async function last() {
  await rec.settled();
  const call = rec.calls.at(-1)!;
  return {
    method: call.method,
    path: call.url.pathname,
    query: Object.fromEntries(call.url.searchParams),
    body: call.body,
    bearer: call.headers.get('authorization'),
    admin: call.headers.get('x-admin-token'),
  };
}

const caps = f.clientCaps();

describe('endpoint modules', () => {
  it.each<[string, () => Promise<unknown>, Partial<Awaited<ReturnType<typeof last>>>]>([
    ['getInfo', () => system.getInfo(), { method: 'GET', path: '/api/info', bearer: null }],
    ['listProfiles', () => profiles.listProfiles(), { method: 'GET', path: '/api/profiles', bearer: null }],
    ['bootstrap', () => profiles.bootstrap({ name: 'E', avatar_id: 'avatar-01', pin: '1234' }),
      { method: 'POST', path: '/api/profiles/bootstrap', body: { name: 'E', avatar_id: 'avatar-01', pin: '1234' } }],
    ['createSession', () => profiles.createSession(2), { method: 'POST', path: '/api/profiles/2/session', body: null, bearer: null }],
    ['getMe', () => profiles.getMe(), { method: 'GET', path: '/api/me', bearer: 'Bearer tok', admin: null }],
    ['getHome', () => catalog.getHome(), { path: '/api/home', query: { limit: '20' } }],
    ['listFilms', () => catalog.listFilms({ page: 2, page_size: 60, sort: 'year', order: 'desc', q: '' }),
      { path: '/api/films', query: { page: '2', page_size: '60', sort: 'year', order: 'desc' } }],
    ['listShows', () => catalog.listShows({ genre: 18, year_from: 1990, year_to: 2009 }),
      { path: '/api/shows', query: { genre: '18', year_from: '1990', year_to: '2009' } }],
    ['getFilm', () => catalog.getFilm(12), { path: '/api/films/12' }],
    ['getShow', () => catalog.getShow(7), { path: '/api/shows/7' }],
    ['getSeason', () => catalog.getSeason(7, 0), { path: '/api/shows/7/seasons/0' }],
    ['getEpisode', () => catalog.getEpisode(345), { path: '/api/episodes/345' }],
    ['getGenres', () => catalog.getGenres('shows'), { path: '/api/genres', query: { type: 'shows' } }],
    ['search', () => catalog.search('dune'), { path: '/api/search', query: { q: 'dune', limit: '20' } }],
    ['getPerson', () => people.getPerson(3), { method: 'GET', path: '/api/people/3', query: {}, bearer: 'Bearer tok' }],
    ['putProgress', () => watch.putProgress({ item_type: 'film', item_id: 12, media_file_id: 91, position_seconds: 10, duration_seconds: 100 }),
      { method: 'PUT', path: '/api/progress', body: { item_type: 'film', item_id: 12, media_file_id: 91, position_seconds: 10, duration_seconds: 100 } }],
    ['setWatched', () => watch.setWatched({ target: 'season', id: 70, watched: true }),
      { method: 'POST', path: '/api/watched', body: { target: 'season', id: 70, watched: true } }],
    ['getMediaFile', () => playback.getMediaFile(91), { path: '/api/media-files/91' }],
    ['requestPlayback', () => playback.requestPlayback(91, { client_caps: caps, audio_stream_index: null, start_position_seconds: 0, previous_session_id: null }),
      { method: 'POST', path: '/api/media-files/91/playback', body: { client_caps: caps, audio_stream_index: null, start_position_seconds: 0, previous_session_id: null } }],
    ['keepAliveHls', () => playback.keepAliveHls('s1'), { method: 'POST', path: '/api/hls/s1/keepalive' }],
    ['deleteHlsSession', () => playback.deleteHlsSession('s1'), { method: 'DELETE', path: '/api/hls/s1' }],
    ['getSubtitleText', () => playback.getSubtitleText('/api/media-files/91/subtitles/emb-3.vtt'),
      { path: '/api/media-files/91/subtitles/emb-3.vtt', query: {}, bearer: 'Bearer tok' }],
    ['unlockAdmin', () => admin.unlockAdmin('1234'), { method: 'POST', path: '/api/admin/unlock', body: { pin: '1234' }, bearer: 'Bearer tok', admin: null }],
    ['getScan', () => admin.getScan(), { path: '/api/admin/scan', admin: 'adm' }],
    ['startScan', () => admin.startScan(), { method: 'POST', path: '/api/admin/scan', admin: 'adm' }],
    ['listUnmatched', () => admin.listUnmatched({ library: 'films', reason: 'multi_part', page: 2 }),
      { path: '/api/admin/unmatched', query: { page_size: '50', library: 'films', reason: 'multi_part', page: '2' }, admin: 'adm' }],
    ['tmdbSearch', () => admin.tmdbSearch('movie', 'dune', null), { path: '/api/admin/tmdb/search', query: { type: 'movie', q: 'dune' }, admin: 'adm' }],
    ['tmdbSeasons', () => admin.tmdbSeasons(2316), { path: '/api/admin/tmdb/tv/2316/seasons', admin: 'adm' }],
    ['tmdbSeason', () => admin.tmdbSeason(2316, 2), { path: '/api/admin/tmdb/tv/2316/seasons/2', admin: 'adm' }],
    ['matchFilm', () => admin.matchFilm(12, 438631), { method: 'POST', path: '/api/admin/films/12/match', body: { tmdb_id: 438631 }, admin: 'adm' }],
    ['matchShow', () => admin.matchShow(7, 2316), { method: 'POST', path: '/api/admin/shows/7/match', body: { tmdb_id: 2316 }, admin: 'adm' }],
    ['resolveUnmatched', () => admin.resolveUnmatched(5, { tmdb_id: 2316, season_number: 2, episode_numbers: [3] }),
      { method: 'POST', path: '/api/admin/unmatched/5/resolve', body: { tmdb_id: 2316, season_number: 2, episode_numbers: [3] }, admin: 'adm' }],
    ['createProfile', () => admin.createProfile({ name: 'Kids', avatar_id: 'avatar-04', is_admin: false }),
      { method: 'POST', path: '/api/admin/profiles', body: { name: 'Kids', avatar_id: 'avatar-04', is_admin: false }, admin: 'adm' }],
    ['updateProfile', () => admin.updateProfile(3, { name: 'Peques' }), { method: 'PATCH', path: '/api/admin/profiles/3', body: { name: 'Peques' }, admin: 'adm' }],
    ['deleteProfile', () => admin.deleteProfile(3), { method: 'DELETE', path: '/api/admin/profiles/3', admin: 'adm' }],
  ])('%s', async (_name, call, expected) => {
    await call();
    expect(await last()).toMatchObject(expected);
  });
});
