// PlayerPage: exit reports from tracked values and in-mount releases. The
// shared <video> is reset by every release (load()/destroy() stubs: currentTime → 0 + a queued
// timeupdate), so no report may ever carry 0 because of a release.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MediaFile, Progress } from '@/api/types';
import { FakeHls } from '../../tests/fakeHls';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, serveEpisodes, useFakePlayerTimers, type PlayerHarness } from '../../tests/player';
import { currentUrl } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const MIN = 60_000;
const hls = (n: number, extra: Partial<Parameters<typeof f.playbackHls>[0]> = {}) =>
  f.playbackHls({ session_id: `sess-${n}`, url: `/api/hls/sess-${n}/index.m3u8`, ...extra });

function serveFilm(files: MediaFile[] = [f.mediaFile()], progress: Progress | null = f.progress({ position_seconds: 400, media_file_id: 91 })) {
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ media_files: files, progress }))));
}

const back = () => fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);

async function finals(p: PlayerHarness) {
  await p.rec.settled();
  return p.puts().filter((c) => c.keepalive);
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

describe('exit report from tracked values', () => {
  it('play a film from 0 to 400 s, Back → exactly one final PUT with position_seconds 400', async () => {
    serveFilm([f.mediaFile()], f.progress({ position_seconds: 0, media_file_id: 91 }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.at(400);
    const video = p.video();
    back();
    await until(() => expect(p.puts()).toHaveLength(1));
    const [put] = await finals(p);
    expect(put?.body).toMatchObject({ position_seconds: 400, media_file_id: 91 });
    expect(video.currentTime).toBe(0); // released by the cleanup after the report was built
    expect((await p.putBodies()).some((b) => b.position_seconds === 0)).toBe(false);
  });

  it('Part 1 to ended → [Reproducir parte 2]: the Part 1 reports after ended carry the duration (one final), none carries 0; the next mount reports Part 2', async () => {
    useFakePlayerTimers();
    const parts = [f.mediaFile({ id: 201, part_number: 1, duration_seconds: 3000 }), f.mediaFile({ id: 202, part_number: 2, duration_seconds: 3100 })];
    serveFilm(parts, f.progress({ media_file_id: 201, position_seconds: 0, duration_seconds: 3000 }));
    answerPlayback((id) => HttpResponse.json(hls(id, { duration_seconds: id === 201 ? 3000 : 3100 })));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    p.at(2990);
    p.at(3000, 'pause');
    p.at(3000, 'ended');
    fireEvent.click(await vi.waitUntil(() => screen.queryByRole('button', { name: 'Reproducir parte 2' })));
    await p.startHls(0, 2);
    await advance(10_000);
    p.at(10);
    await advance(10_000);
    const bodies = await p.putBodies();
    const part1 = bodies.filter((b) => b.media_file_id === 201);
    expect(part1.every((b) => b.position_seconds === 3000)).toBe(true);
    expect((await finals(p)).filter((c) => (c.body as { media_file_id: number }).media_file_id === 201)).toHaveLength(1);
    const firstPart2 = bodies.findIndex((b) => b.media_file_id === 202);
    expect(firstPart2).toBeGreaterThan(bodies.lastIndexOf(part1[part1.length - 1] as (typeof bodies)[number]));
    expect(bodies.slice(firstPart2).every((b) => b.media_file_id === 202)).toBe(true);
  });

  it('next-episode countdown: the old episode’s last report is its ended position', async () => {
    useFakePlayerTimers();
    serveEpisodes([
      f.episodeDetail({ id: 345, media_files: [f.episodeFile({ id: 501 })], next_episode_id: 346, progress: null }),
      f.episodeDetail({ id: 346, episode_number: 4, media_files: [f.episodeFile({ id: 502 })], next_episode_id: null, progress: null }),
    ]);
    answerPlayback((id) => HttpResponse.json(hls(id, { duration_seconds: 1320 })));
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(1300);
    p.at(1320, 'ended');
    await advance(10_000);
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
    const old = (await p.putBodies()).filter((b) => b.media_file_id === 501);
    expect(old.length).toBeGreaterThanOrEqual(1);
    expect(old[old.length - 1]?.position_seconds).toBe(1320);
    expect(old.some((b) => b.position_seconds === 0)).toBe(false);
  });
});

describe('in-mount releases', () => {
  it('(a) HLS paused at 400 s, released after 30 min, then Back → the final PUT carries 400', async () => {
    useFakePlayerTimers();
    serveFilm();
    answerPlayback(hls(1));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(400);
    p.pauseAt(400);
    await advance(30 * MIN);
    expect(p.deletes()).toHaveLength(1);
    back();
    await advance(0);
    await until(() => expect(p.puts().length).toBeGreaterThanOrEqual(2));
    const [final] = await finals(p);
    expect(final?.body).toMatchObject({ position_seconds: 400 });
    expect((await p.putBodies()).some((b) => b.position_seconds === 0)).toBe(false);
  });

  it('(b) the same release, then Play → POST start_position_seconds 400', async () => {
    useFakePlayerTimers();
    serveFilm();
    answerPlayback(hls(1), hls(2));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(400);
    p.pauseAt(400);
    await advance(30 * MIN);
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir' }));
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 400, previous_session_id: null });
  });

  it('(c) audio switch at 400 s → Back while loading → the final report carries 400', async () => {
    const tracks = [f.audioTrack({ index: 1 }), f.audioTrack({ index: 2, language: 'spa' })];
    serveFilm([f.mediaFile({ audio_tracks: tracks })]);
    answerPlayback(hls(1), hls(2, { audio_stream_index: 2 }));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(400);
    fireEvent.click(screen.getByRole('button', { name: 'Audio' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /^Español/ }));
    await until(() => expect(FakeHls.instances).toHaveLength(2));
    back();
    await until(() => expect(p.puts()).toHaveLength(1));
    expect((await finals(p))[0]?.body).toMatchObject({ position_seconds: 400 });
  });

  it('(d) recovery at 400 s whose new source emits timeupdate at 0 before its start seek → no report with 0; the next periodic report is ≥ 400', async () => {
    useFakePlayerTimers();
    serveFilm();
    answerPlayback(hls(1), hls(2));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(400);
    FakeHls.last?.fatal();
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 400 });
    await until(() => expect(FakeHls.instances[1]?.media).toBe(p.video()));
    p.at(0);
    await advance(10_000);
    p.at(400, 'playing');
    await advance(10_000);
    p.at(405);
    await advance(10_000);
    const positions = (await p.putBodies()).map((b) => b.position_seconds);
    expect(positions).not.toContain(0);
    expect(positions.length).toBeGreaterThanOrEqual(1);
    expect(positions.every((x) => x >= 400)).toBe(true);
  });

  it('(e) PLAY_FILE to a sibling starting at 0 after the old file played to 400 s → no old-file report carries 0; the new file reports from its own start', async () => {
    useFakePlayerTimers();
    serveFilm([f.mediaFile({ id: 91 }), f.mediaFile({ id: 92, file_name: 'Dune 720p.mp4', label: '720p H264 · AAC 2.0', size_bytes: 4e9 })], null);
    answerPlayback(
      hls(1),
      apiError(500, 'transcoder_failed', { details: { detail: 'keyframe_index_failed: stalled' } }),
      HttpResponse.json(f.playbackDirect({ url: '/api/media-files/92/stream' })),
    );
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    p.at(400);
    await advance(10_000);
    FakeHls.last?.fatal();
    const alert = await vi.waitUntil(() => screen.queryByRole('alert'));
    fireEvent.click(within(alert).getByRole('button', { name: 'Probar Dune 720p' }));
    await until(() => expect(p.posts()).toHaveLength(3));
    expect((await p.postBodies())[2]).toMatchObject({ start_position_seconds: 0 });
    await p.startDirect(0, /\/api\/media-files\/92\/stream/);
    await advance(10_000);
    p.at(12);
    await advance(10_000);
    const bodies = await p.putBodies();
    expect(bodies.filter((b) => b.media_file_id === 91).map((b) => b.position_seconds)).toEqual([400]);
    const newer = bodies.filter((b) => b.media_file_id === 92).map((b) => b.position_seconds);
    expect(newer.length).toBeGreaterThanOrEqual(1);
    expect(newer.every((x) => x < 400)).toBe(true);
  });

  it('(f) resume at 400 s in HLS; a fatal NETWORK_ERROR before any seeked/playing → recovery POST at 400, no report carries 0', async () => {
    useFakePlayerTimers();
    serveFilm();
    answerPlayback(hls(1), hls(2));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(FakeHls.last?.media).toBe(p.video()));
    p.at(0);
    FakeHls.last?.fatal();
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies()).map((b) => b.start_position_seconds)).toEqual([400, 400]);
    await p.startHls(400, 2);
    await advance(10_000);
    back();
    await until(() => expect(p.puts().length).toBeGreaterThanOrEqual(1));
    expect((await p.putBodies()).some((b) => b.position_seconds === 0)).toBe(false);
  });
});
