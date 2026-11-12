// PlayerPage: audio switch — request body, session ownership and every
// outcome in HLS and direct mode, including the preparing wait and its Cancel.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { FakeHls } from '../../tests/fakeHls';
import { advance, until } from '../../tests/flush';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, useFakePlayerTimers, type PlayerHarness } from '../../tests/player';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const TRACKS = [
  f.audioTrack({ index: 1, codec: 'eac3', channels: 6, language: 'eng' }),
  f.audioTrack({ index: 2, codec: 'ac3', channels: 2, language: 'spa', default: false }),
];
const AUDIO_UNSUPPORTED = 'Esa pista de audio no se puede reproducir en este navegador';
const DISK_FULL =
  'El servidor no tiene espacio para convertir vídeo ahora mismo. Vuelve a intentarlo en unos minutos o detén la reproducción en otro dispositivo.';
const hls = (n: number, audio = 1) =>
  f.playbackHls({ session_id: `sess-${n}`, url: `/api/hls/sess-${n}/index.m3u8`, audio_stream_index: audio });

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  server.use(
    http.get(`${API}/films/:id`, () =>
      HttpResponse.json(f.filmDetail({ media_files: [f.mediaFile({ audio_tracks: TRACKS })], progress: f.progress({ position_seconds: 100 }) })),
    ),
  );
});

function chooseSpanish() {
  fireEvent.click(screen.getByRole('button', { name: 'Audio' }));
  fireEvent.click(screen.getByRole('menuitemradio', { name: 'Español · AC3 2.0' }));
}

async function playingAt400(p: PlayerHarness, mode: 'hls' | 'direct') {
  if (mode === 'hls') await p.startHls(100);
  else await p.startDirect(100);
  p.at(400);
}

describe('audio pick saves the choice', () => {
  it('a switch to Español sends one PUT /api/track-choices/films/12 with the picked audio part', async () => {
    answerPlayback(hls(1), hls(2, 2));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    await until(() => expect(p.rec.find('PUT', '/api/track-choices/films/12')).toHaveLength(1));
    await p.rec.settled();
    expect(p.rec.find('PUT', '/api/track-choices/films/12')[0]?.body).toEqual({
      audio: { language: 'spa', title: null, channels: 2 },
      subtitles: null,
    });
  });
});

describe('audio switch in HLS mode', () => {
  it('POSTs audio_stream_index, start = the saved position and previous_session_id; the old session gets no keep-alive or DELETE afterwards', async () => {
    useFakePlayerTimers();
    answerPlayback(hls(1), hls(2, 2));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toEqual(
      expect.objectContaining({ audio_stream_index: 2, start_position_seconds: 400, previous_session_id: 'sess-1' }),
    );
    await p.startHls(400, 2);
    expFakeOrder();
    p.pauseAt(410);
    await advance(60_000);
    expect(new Set(p.keepalives().map((c) => c.url.pathname))).toEqual(new Set(['/api/hls/sess-2/keepalive']));
    fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);
    await advance(0);
    await until(() => expect(p.deletes()).toHaveLength(1));
    expect(p.deletes()[0]?.url.pathname).toBe('/api/hls/sess-2');
  });

  it('answered direct: attaches the direct source at t, no DELETE of the handed-over session', async () => {
    answerPlayback(hls(1), f.playbackDirect({ audio_stream_index: 2 }));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/media-files/91/stream?token=token-1'));
    p.fire('loadedmetadata');
    expect(p.video().currentTime).toBe(400);
    expect(FakeHls.instances[0]?.destroyed).toBe(true);
    expect(p.deletes()).toHaveLength(0);
  });

  it('answered unsupported: the toast, then a new POST with the previous audio, previous_session_id null, at t', async () => {
    answerPlayback(hls(1), f.playbackUnsupported({ reason: 'keyframes_too_sparse' }), hls(3));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    expect(await screen.findByText(AUDIO_UNSUPPORTED)).toBeInTheDocument();
    await until(() => expect(p.posts()).toHaveLength(3));
    expect((await p.postBodies())[2]).toEqual(
      expect.objectContaining({ audio_stream_index: 1, start_position_seconds: 400, previous_session_id: null }),
    );
    await p.startHls(400, 2);
    expect(FakeHls.last?.config.startPosition).toBe(400);
    expect(p.deletes()).toHaveLength(0);
  });

  it('answered hls_disk_full: the busy screen; Retry re-requests at t with the chosen audio', async () => {
    answerPlayback(hls(1), apiError(503, 'hls_disk_full'), hls(3, 2));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(DISK_FULL);
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(3));
    expect((await p.postBodies())[2]).toEqual(
      expect.objectContaining({ audio_stream_index: 2, start_position_seconds: 400, previous_session_id: null }),
    );
  });

  it('preparing + Cancel: a new POST with the previous audio at t; no keep-alive for the old session during the wait', async () => {
    useFakePlayerTimers();
    answerPlayback(hls(1), apiError(503, 'preparing', { headers: { 'Retry-After': '5' }, details: { estimated_seconds: 600 } }), hls(3));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    chooseSpanish();
    await until(() => expect(screen.getByText('Cambiando el audio… preparando el vídeo')).toBeInTheDocument());
    await advance(4_000);
    expect(p.keepalives()).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    await until(() => expect(p.posts()).toHaveLength(3));
    expect((await p.postBodies())[2]).toEqual(
      expect.objectContaining({ audio_stream_index: 1, start_position_seconds: 400, previous_session_id: null }),
    );
    await advance(60_000);
    expect(p.keepalives().filter((c) => c.url.pathname.includes('sess-1'))).toHaveLength(0);
  });

  it('works while buffering', async () => {
    answerPlayback(hls(1), hls(2, 2));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'hls');
    p.fire('waiting');
    expect(screen.getByRole('button', { name: 'Audio' })).toBeEnabled();
    chooseSpanish();
    await until(() => expect(p.posts()).toHaveLength(2));
  });
});

describe('audio switch in direct mode', () => {
  it('answered unsupported: the toast, no second POST, the old source resumes', async () => {
    answerPlayback(f.playbackDirect(), f.playbackUnsupported({ reason: 'keyframes_too_sparse' }));
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'direct');
    const src = p.video().getAttribute('src');
    chooseSpanish();
    expect(await screen.findByText(AUDIO_UNSUPPORTED)).toBeInTheDocument();
    expect((await p.postBodies())[1]).toEqual(expect.objectContaining({ audio_stream_index: 2, previous_session_id: null }));
    await until(() => expect(play).toHaveBeenCalledTimes(2));
    expect(p.posts()).toHaveLength(2);
    expect(p.video().getAttribute('src')).toBe(src);
  });

  it('answered hls_disk_full: a toast with the message, stays on the old source (no busy screen)', async () => {
    answerPlayback(f.playbackDirect(), apiError(503, 'hls_disk_full'));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'direct');
    const src = p.video().getAttribute('src');
    chooseSpanish();
    expect(await screen.findByText(DISK_FULL)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(p.video().getAttribute('src')).toBe(src);
    expect(p.posts()).toHaveLength(2);
  });

  it('preparing + Cancel keeps the old source and audio track (no new POST)', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackDirect(), apiError(503, 'preparing', { headers: { 'Retry-After': '5' } }));
    const p = renderPlayer('/watch/film/12');
    await playingAt400(p, 'direct');
    const src = p.video().getAttribute('src');
    chooseSpanish();
    await until(() => expect(screen.getByText('Cambiando el audio… preparando el vídeo')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    await advance(30_000);
    expect(p.posts()).toHaveLength(2);
    expect(p.video().getAttribute('src')).toBe(src);
    fireEvent.click(screen.getByRole('button', { name: 'Audio' }));
    expect(screen.getByRole('menuitemradio', { name: 'Inglés · EAC3 5.1' })).toHaveAttribute('aria-checked', 'true');
  });
});

function expFakeOrder() {
  const [first, second] = FakeHls.instances;
  expect(first?.destroyed).toBe(true);
  expect(second?.media).not.toBeNull();
}
