// PlayerPage: the remembered audio/subtitle choice (prd2.md FR-W5, FR-W6, FR-W7) — the first POST's
// audio index, the one silent re-send when it is refused, the first subtitle decision, the PUT per
// manual pick (serialized per title), the next episode / another version starting with the pick.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { AudioTrack, EpisodeDetail, MediaFile, TrackChoice } from '@/api/types';
import * as f from '../../tests/fixtures';
import { FakeHls } from '../../tests/fakeHls';
import { advance, until } from '../../tests/flush';
import { API, apiError, withSavedChoice } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { createTestQueryClient } from '../../tests/renderWithProviders';
import { PLAYBACK, deferred, gateTrackChoicePuts, renderPlayer, serveEpisodes, useFakePlayerTimers, type PlayerHarness } from '../../tests/player';
import { pendingChoice } from './trackChoiceSession';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const SPA = f.audioTrack({ index: 1, codec: 'aac', channels: 2, language: 'spa', default: true });
const ENG = f.audioTrack({ index: 2, codec: 'aac', channels: 2, language: 'eng', default: false });
const TRACKS: AudioTrack[] = [SPA, ENG];
const ENG_CHOICE = { language: 'eng', title: null, channels: 2 };
const SPA_CHOICE = { language: 'spa', title: null, channels: 2 };
const subsFor = (fileId: number) => [
  f.subtitleTrack({ id: 'emb-3', language: 'spa' }, fileId),
  f.subtitleTrack({ id: 'emb-4', language: 'eng', default: true }, fileId),
];
const SPA_SUBS = f.subtitleChoice();
const OFF = { mode: 'off', language: null, title: null, forced: false, hearing_impaired: false, source: null, kind: null };
const DURATION = 1320;

const film = (choice: TrackChoice | null, files: MediaFile[] = [f.mediaFile({ audio_tracks: TRACKS, subtitle_tracks: subsFor(91) })]) =>
  f.filmDetail({ media_files: files, track_choice: choice, progress: null });

const episodeFileFor = (id: number) =>
  f.episodeFile({ id, duration_seconds: DURATION, audio_tracks: TRACKS, subtitle_tracks: subsFor(id) });
const episode = (id: number, n: number, next: number | null, choice: TrackChoice | null = null): EpisodeDetail =>
  f.episodeDetail({ id, episode_number: n, media_files: [episodeFileFor(500 + n)], next_episode_id: next, progress: null, track_choice: choice });

/** Answers every POST playback in `mode`, echoing the requested audio (else the default 1). */
function servePlayback(mode: 'direct' | 'hls' = 'direct', override?: (n: number, body: Record<string, unknown>) => Response | null) {
  let n = 0;
  server.use(
    http.post(PLAYBACK, async ({ params, request }) => {
      n += 1;
      const body = (await request.json()) as Record<string, unknown>;
      const custom = override?.(n, body);
      if (custom) return custom;
      const fileId = Number(params.id);
      const audio = (body.audio_stream_index as number | null) ?? 1;
      const common = { audio_stream_index: audio, duration_seconds: DURATION, subtitle_tracks: subsFor(fileId) };
      return HttpResponse.json(
        mode === 'direct'
          ? f.playbackDirect({ url: `/api/media-files/${fileId}/stream`, ...common })
          : f.playbackHls({ session_id: `sess-${n}`, url: `/api/hls/sess-${n}/index.m3u8`, ...common }),
      );
    }),
  );
}

const puts = (p: PlayerHarness) => p.rec.find('PUT', /^\/api\/track-choices\//);
async function putBodies(p: PlayerHarness) {
  await p.rec.settled();
  return puts(p).map((c) => c.body as TrackChoice);
}

function pickSubtitle(name: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
  fireEvent.click(within(screen.getByRole('menu', { name: 'Subtítulos' })).getByRole('menuitemradio', { name }));
}

function pickAudio(name: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Audio' }));
  fireEvent.click(within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', { name }));
}

const selectedSubtitle = () =>
  within(screen.getByRole('menu', { name: 'Subtítulos' }))
    .getAllByRole('menuitemradio')
    .find((i) => i.getAttribute('aria-checked') === 'true')?.textContent;

function openSubtitles() {
  fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

describe('applying the remembered audio at start (W-AC-3, W-AC-4)', () => {
  it.each([
    ['a non-default match sends its index', f.trackChoice({ audio: ENG_CHOICE }), 2],
    ['a match equal to the default sends null', f.trackChoice({ audio: SPA_CHOICE }), null],
    ['no audio part sends null', f.trackChoice({ audio: null, subtitles: SPA_SUBS }), null],
    ['no choice sends null', null, null],
  ])('%s', async (_name, choice, expected) => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(choice))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.posts()).toHaveLength(1));
    expect((await p.postBodies())[0]).toMatchObject({ audio_stream_index: expected });
  });

  it('unsupported for the remembered index: exactly one re-send with null, no screen, no toast, no PUT; RETRY sends null', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: ENG_CHOICE })))));
    servePlayback('direct', (n) =>
      n === 1 ? HttpResponse.json(f.playbackUnsupported({ reason: 'audio_codec_unsupported' })) : n === 2 ? apiError(500, 'internal') : null,
    );
    const p = renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(alert).not.toHaveTextContent('No se puede reproducir este vídeo');
    expect((await p.postBodies()).map((b) => b.audio_stream_index)).toEqual([2, null]);
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(3));
    await p.startDirect(0);
    expect((await p.postBodies()).map((b) => b.audio_stream_index)).toEqual([2, null, null]);
    expect(puts(p)).toHaveLength(0);
  });

  it('a preparing tick after the re-send sends null; a recovery sends the answered default index', async () => {
    useFakePlayerTimers();
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: ENG_CHOICE })))));
    servePlayback('hls', (n) =>
      n === 1
        ? HttpResponse.json(f.playbackUnsupported({ reason: 'audio_codec_unsupported' }))
        : n === 2
          ? apiError(503, 'preparing', { headers: { 'Retry-After': '1' } })
          : null,
    );
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(screen.getByText(/Preparando el vídeo/)).toBeInTheDocument());
    await advance(1000);
    await until(() => expect(p.posts()).toHaveLength(3));
    await advance(0);
    await p.startHls(0, 1);
    p.at(200);
    FakeHls.last?.fatal();
    await until(() => expect(p.posts()).toHaveLength(4));
    expect((await p.postBodies()).map((b) => b.audio_stream_index)).toEqual([2, null, null, 1]);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('invalid_audio_stream for the remembered index: one re-send with null', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: ENG_CHOICE })))));
    servePlayback('direct', (n) => (n === 1 ? apiError(422, 'invalid_audio_stream') : null));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect((await p.postBodies()).map((b) => b.audio_stream_index)).toEqual([2, null]);
  });
});

describe('the first subtitle decision (W-AC-5)', () => {
  it('selects the remembered subtitle track', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: null, subtitles: SPA_SUBS })))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    openSubtitles();
    expect(selectedSubtitle()).toContain('Español');
  });

  it('mode off starts Off even with a default track', async () => {
    server.use(
      http.get(`${API}/films/:id`, () =>
        HttpResponse.json(film(f.trackChoice({ audio: null, subtitles: f.subtitleChoice({ mode: 'off', language: null, source: null, kind: null }) }))),
      ),
    );
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    openSubtitles();
    expect(selectedSubtitle()).toContain('Desactivados');
  });

  it('no subtitle part: today’s default (the default flag)', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(null))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    openSubtitles();
    expect(selectedSubtitle()).toContain('Inglés');
  });
});

describe('saving manual picks (W-AC-6, W-AC-9)', () => {
  it('a subtitle pick sends one PUT with the held audio part; an audio pick keeps the held subtitles; start defaults send none', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: SPA_CHOICE })))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(puts(p)).toHaveLength(0);
    pickSubtitle('Desactivados');
    await until(() => expect(puts(p)).toHaveLength(1));
    expect(puts(p)[0]?.url.pathname).toBe('/api/track-choices/films/12');
    expect((await putBodies(p))[0]).toEqual({ audio: SPA_CHOICE, subtitles: OFF });
    pickAudio('Inglés · AAC 2.0');
    await until(() => expect(puts(p)).toHaveLength(2));
    expect((await putBodies(p))[1]).toEqual({ audio: ENG_CHOICE, subtitles: OFF });
    expect(puts(p)[1]?.headers.get('authorization')).toBe('Bearer token-1');
  });

  it('a subtitle that fails to load reverts to Off without a PUT', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(null))));
    server.use(http.get('/api/media-files/:id/subtitles/:track', () => apiError(500, 'subtitle_failed')));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    await until(() => expect(p.rec.find('GET', /\/subtitles\//).length).toBeGreaterThan(0));
    await until(() => {
      openSubtitles();
      expect(selectedSubtitle()).toContain('Desactivados');
    });
    expect(puts(p)).toHaveLength(0);
  });

  it('hometv.subtitleLang.<pid> is removed on mount and never read', async () => {
    localStorage.setItem('hometv.subtitleLang.1', 'spa');
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(null))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(localStorage.getItem('hometv.subtitleLang.1')).toBeNull();
    openSubtitles();
    expect(selectedSubtitle()).toContain('Inglés');
  });
});

describe('re-picking the playing audio track (W-AC-22)', () => {
  const engOnlyMissing = () =>
    f.mediaFile({ audio_tracks: [SPA, f.audioTrack({ index: 3, codec: 'aac', channels: 2, language: 'fre', default: false })], subtitle_tracks: subsFor(91) });

  it('remembered eng missing: picking the playing "Español" sends one PUT and no POST', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: ENG_CHOICE }), [engOnlyMissing()]))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    pickAudio('Español · AAC 2.0');
    await until(() => expect(puts(p)).toHaveLength(1));
    expect((await putBodies(p))[0]).toEqual({ audio: SPA_CHOICE, subtitles: null });
    expect(p.posts()).toHaveLength(1);
  });

  it('no audio part: picking the playing default sends one PUT', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(null))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    pickAudio('Español · AAC 2.0');
    await until(() => expect(puts(p)).toHaveLength(1));
    expect(p.posts()).toHaveLength(1);
  });

  it('picking the playing track equal to the held part sends nothing', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(f.trackChoice({ audio: SPA_CHOICE })))));
    servePlayback();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    pickAudio('Español · AAC 2.0');
    // A save records its session override synchronously before its PUT: none means no PUT ever.
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
    await p.rec.settled();
    expect(puts(p)).toHaveLength(0);
    expect(p.posts()).toHaveLength(1);
  });
});

describe('quick picks are serialized per title (W-AC-8b)', () => {
  it('three picks: at most two PUTs, one at a time, the last body is the last pick', async () => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film(null))));
    servePlayback();
    const gate = gateTrackChoicePuts();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    pickSubtitle('Español');
    pickSubtitle('Desactivados');
    pickSubtitle('Inglés');
    await until(() => expect(gate.pending()).toBe(1));
    expect(puts(p)).toHaveLength(1);
    gate.release();
    await until(() => expect(gate.pending()).toBe(1));
    expect(puts(p)).toHaveLength(2);
    gate.release();
    const bodies = await putBodies(p);
    expect(bodies[0]?.subtitles).toEqual(SPA_SUBS);
    expect(bodies[1]?.subtitles).toMatchObject({ mode: 'track', language: 'eng' });
  });
});

describe('the next episode starts with the pick (W-AC-7, W-AC-8)', () => {
  const EPISODES = () => [episode(345, 1, 346), episode(346, 2, null)];

  async function pickThenNext(p: PlayerHarness) {
    await p.startHls(0);
    pickAudio('Inglés · AAC 2.0');
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startHls(5, 2);
  }

  async function playNext(p: PlayerHarness) {
    p.at(DURATION - 10);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(p.posts().map((c) => c.url.pathname)).toContain('/api/media-files/502/playback'));
    const bodies = await p.postBodies();
    return bodies[p.posts().findIndex((c) => c.url.pathname === '/api/media-files/502/playback')];
  }

  it('while the PUT is pending (gated)', async () => {
    serveEpisodes(EPISODES());
    servePlayback('hls');
    const gate = gateTrackChoicePuts();
    const p = renderPlayer('/watch/episode/345');
    await pickThenNext(p);
    await until(() => expect(gate.pending()).toBe(1));
    expect(await playNext(p)).toMatchObject({ audio_stream_index: 2 });
    gate.release();
  });

  it('after the 204 with the next episode prefetched before the pick; the episode and home keys are invalidated', async () => {
    serveEpisodes(EPISODES());
    servePlayback('hls');
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 20);
    await until(() => expect(p.queryClient.getQueryData(queryKeys.episode(346))).toBeDefined());
    pickAudio('Inglés · AAC 2.0');
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startHls(DURATION - 20, 2);
    await until(() => expect(p.rec.find('GET', '/api/episodes/345').length).toBeGreaterThan(1));
    expect(p.queryClient.getQueryState(queryKeys.episode(346))?.isInvalidated).toBe(true);
    expect((p.queryClient.getQueryData(queryKeys.episode(346)) as EpisodeDetail).track_choice?.audio).toEqual(ENG_CHOICE);
    expect(await playNext(p)).toMatchObject({ audio_stream_index: 2 });
  });

  it('a failed PUT logs, shows no toast, and the pick still applies to the next episode', async () => {
    serveEpisodes(EPISODES());
    servePlayback('hls');
    server.use(http.put(`${API}/track-choices/:segment/:id`, () => apiError(500, 'internal')));
    const p = renderPlayer('/watch/episode/345');
    await pickThenNext(p);
    await until(() => expect(vi.mocked(console.warn).mock.calls.flat().join(' ')).toContain('[api] internal (500)'));
    expect(screen.queryByText(/No se ha podido/)).not.toBeInTheDocument();
    expect(await playNext(p)).toMatchObject({ audio_stream_index: 2 });
  });
});

describe('"Seguir viendo" starts (W-AC-10, W-AC-21)', () => {
  const homeWith = (choice: TrackChoice | null) =>
    f.homeResponse({
      continue_watching: [
        f.homeEpisode({
          id: 345,
          media_files: [episodeFileFor(501)],
          track_choice: choice,
          progress: f.progress({ media_file_id: 501, position_seconds: 300, duration_seconds: DURATION }),
        }),
      ],
    });

  it('applies the HomeEpisode track_choice in the first POST before GET /api/episodes/{id} answers', async () => {
    const qc = createTestQueryClient();
    qc.setQueryData(queryKeys.home(), homeWith(f.trackChoice({ audio: ENG_CHOICE })));
    const hold = deferred();
    serveEpisodes([episode(345, 1, null)], { 345: hold.promise });
    servePlayback();
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    await until(() => expect(p.posts()).toHaveLength(1));
    expect((await p.postBodies())[0]).toMatchObject({ audio_stream_index: 2 });
    hold.resolve();
  });

  it('two picks across a settle keep both parts', async () => {
    const qc = createTestQueryClient();
    qc.setQueryData(queryKeys.home(), homeWith(null));
    serveEpisodes([episode(345, 1, null)]);
    servePlayback();
    const gate = gateTrackChoicePuts();
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    await p.startDirect(300);
    pickAudio('Inglés · AAC 2.0');
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startDirect(300);
    await until(() => expect(gate.pending()).toBe(1));
    gate.release();
    await until(() =>
      expect((qc.getQueryData(queryKeys.episode(345)) as EpisodeDetail | undefined)?.track_choice?.audio).toEqual(ENG_CHOICE),
    );
    await until(() => expect(p.rec.find('GET', '/api/episodes/345').length).toBeGreaterThan(1));
    pickSubtitle('Español');
    await until(() => expect(gate.pending()).toBe(1));
    gate.release();
    expect((await putBodies(p))[1]).toEqual({ audio: ENG_CHOICE, subtitles: SPA_SUBS });
  });
});

describe('another version after settled picks (W-AC-24)', () => {
  it('PLAY_FILE sends that file’s English index and its first decision selects its Spanish subtitles', async () => {
    const file1 = f.mediaFile({ id: 91, audio_tracks: TRACKS, subtitle_tracks: subsFor(91) });
    const file2 = f.mediaFile({
      id: 92,
      file_name: 'Dune 720p.mp4',
      label: '720p H264 · AAC 2.0',
      audio_tracks: [f.audioTrack({ index: 4, language: 'spa', default: true }), f.audioTrack({ index: 7, language: 'eng', default: false })],
      subtitle_tracks: subsFor(92),
    });
    server.use(
      http.get(`${API}/films/:id`, () => HttpResponse.json(withSavedChoice(f.filmDetail({ media_files: [file1, file2], progress: null }), 'film', 12))),
    );
    servePlayback('hls', (_n, body) =>
      body.start_position_seconds === 333
        ? apiError(500, 'transcoder_failed', { details: { detail: 'keyframe_index_failed: stalled' } })
        : null,
    );
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    pickAudio('Inglés · AAC 2.0');
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startHls(0);
    pickSubtitle('Español');
    await until(() => expect(puts(p)).toHaveLength(2));
    await until(() => expect(p.queryClient.getQueryData<{ track_choice: TrackChoice | null }>(queryKeys.film(12))?.track_choice).toEqual({
      audio: ENG_CHOICE,
      subtitles: SPA_SUBS,
    }));
    p.at(333);
    FakeHls.last?.fatal();
    const alert = await screen.findByRole('alert');
    fireEvent.click(within(alert).getByRole('button', { name: 'Probar Dune 720p' }));
    await until(() => expect(p.posts().at(-1)?.url.pathname).toBe('/api/media-files/92/playback'));
    expect((await p.postBodies()).at(-1)).toMatchObject({ audio_stream_index: 7 });
    await p.startHls(0);
    openSubtitles();
    expect(selectedSubtitle()).toContain('Español');
  });
});
