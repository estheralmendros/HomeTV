// PlayerPage: HLS session lifecycle through the UI — exit DELETE, keep-alive, the 30 min pause
// release, autoplay rejection and media-error recovery.
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { setCapabilityEnv } from './capabilities';
import { iphoneEnv } from '../../tests/capsEnvs';
import { FakeHls } from '../../tests/fakeHls';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { mediaState } from '../../tests/media';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, useFakePlayerTimers } from '../../tests/player';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const MIN = 60_000;
const hls2 = () => f.playbackHls({ session_id: 'sess-2', url: '/api/hls/sess-2/index.m3u8' });
const hls3 = () => f.playbackHls({ session_id: 'sess-3', url: '/api/hls/sess-3/index.m3u8' });

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ progress: f.progress({ position_seconds: 100 }) }))));
});

describe('exit', () => {
  it('unmount (Back) sends the final PUT and DELETE /api/hls/{id}', async () => {
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.at(400);
    fireEvent.click(screen.getByRole('button', { name: 'Volver' }));
    await until(() => expect(p.deletes()).toHaveLength(1));
    expect(p.deletes()[0]?.url.pathname).toBe('/api/hls/sess-1');
    await until(() => expect(p.puts()).toHaveLength(1));
    expect((await p.putBodies())[0]).toMatchObject({ item_type: 'film', item_id: 12, media_file_id: 91, position_seconds: 400 });
    expect(p.deletes()[0]?.headers.get('authorization')).toBe('Bearer token-1');
  });

  it('pagehide (closing the tab, a full navigation) DELETEs the session with keepalive, once', async () => {
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.at(400);
    window.dispatchEvent(new Event('pagehide'));
    await until(() => expect(p.deletes()).toHaveLength(1));
    expect(p.deletes()[0]?.url.pathname).toBe('/api/hls/sess-1');
    expect(p.deletes()[0]?.keepalive).toBe(true);
    await until(() => expect(p.puts()).toHaveLength(1));
    expect((await p.putBodies())[0]).toMatchObject({ position_seconds: 400 });
    // A later unmount (the page stayed, e.g. pagehide of a bfcache candidate) sends no second DELETE.
    fireEvent.click(screen.getByRole('button', { name: 'Volver' }));
    await until(() => expect(p.puts()).toHaveLength(2));
    expect(p.deletes()).toHaveLength(1);
  });
});

describe('keep-alive', () => {
  it('every 30 s while paused or blocked in HLS mode, none while playing with hls.js', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    await advance(90_000);
    expect(p.keepalives()).toHaveLength(0);
    p.pauseAt(190);
    await advance(30_000);
    expect(p.keepalives().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-1/keepalive']);
    await advance(60_000);
    expect(p.keepalives()).toHaveLength(3);
  });

  it('while blocked (autoplay rejected) in HLS mode', async () => {
    useFakePlayerTimers();
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(FakeHls.last?.media).toBe(p.video()));
    FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED);
    await until(() => expect(screen.getByRole('button', { name: 'Toca para reproducir' })).toBeInTheDocument());
    await advance(60_000);
    expect(p.keepalives()).toHaveLength(2);
  });

  it('also while playing in the iOS env (native HLS)', async () => {
    useFakePlayerTimers();
    setCapabilityEnv(iphoneEnv());
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/hls/sess-1/index.m3u8'));
    p.fire('loadedmetadata');
    p.at(100, 'playing');
    await advance(60_000);
    expect(p.keepalives()).toHaveLength(2);
  });
});

describe('30 min pause release', () => {
  it('29 min → keep-alives only; 30 min → one DELETE; Play → new POST at the paused position, loading, playback', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackHls(), hls2());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.at(4325);
    p.pauseAt(4325);
    await advance(29 * MIN);
    expect(p.keepalives()).toHaveLength(58);
    expect(p.deletes()).toHaveLength(0);
    await advance(MIN);
    expect(p.deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-1']);
    const kept = p.keepalives().length;
    await advance(10 * MIN);
    expect(p.keepalives()).toHaveLength(kept);

    fireEvent.click(screen.getByRole('button', { name: 'Reproducir' }));
    expect(screen.getByRole('progressbar', { name: 'Cargando el vídeo…' })).toBeInTheDocument();
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 4325, previous_session_id: null, audio_stream_index: 1 });
    await p.startHls(4325, 2);
    expect(FakeHls.last?.config.startPosition).toBe(4325);
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
  });

  it('a play/pause cycle at 20 min restarts the 30 min timer', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.pauseAt(300);
    await advance(20 * MIN);
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir' }));
    p.at(300, 'playing');
    p.pauseAt(301);
    await advance(29 * MIN);
    expect(p.deletes()).toHaveLength(0);
    await advance(MIN);
    expect(p.deletes()).toHaveLength(1);
  });

  it('the same in the iOS env', async () => {
    useFakePlayerTimers();
    setCapabilityEnv(iphoneEnv());
    answerPlayback(f.playbackHls(), hls2());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/hls/sess-1/index.m3u8'));
    p.fire('loadedmetadata');
    p.at(100, 'playing');
    p.pauseAt(500);
    await advance(30 * MIN);
    expect(p.deletes()).toHaveLength(1);
    expect(p.video().hasAttribute('src')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir' }));
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/hls/sess-2/index.m3u8'));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 500, previous_session_id: null });
  });

  it('direct mode paused for 31 min sends nothing', async () => {
    useFakePlayerTimers();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    p.pauseAt(200);
    const before = p.rec.calls.length;
    await advance(31 * MIN);
    expect(p.rec.calls.length - before).toBe(0);
  });

  it('unmount after a release sends no second DELETE', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.pauseAt(300);
    await advance(30 * MIN);
    expect(p.deletes()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Volver' }));
    await advance(0);
    await until(() => expect(p.puts().length).toBeGreaterThanOrEqual(2));
    expect(p.deletes()).toHaveLength(1);
  });
});

describe('autoplay rejection', () => {
  it('NotAllowedError → "Toca para reproducir"; a click calls play() again and playback starts; no report while blocked', async () => {
    useFakePlayerTimers();
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBeTruthy());
    p.fire('loadedmetadata');
    await until(() => expect(screen.getByRole('button', { name: 'Toca para reproducir' })).toBeInTheDocument());
    const tap = screen.getByRole('button', { name: 'Toca para reproducir' });
    await advance(30_000);
    expect(p.puts()).toHaveLength(0);
    fireEvent.click(tap);
    expect(play).toHaveBeenCalledTimes(2);
    p.at(100, 'playing');
    expect(screen.queryByRole('button', { name: 'Toca para reproducir' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
  });

  it('Space activates "Toca para reproducir"', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBeTruthy());
    p.fire('loadedmetadata');
    await screen.findByRole('button', { name: 'Toca para reproducir' });
    fireEvent.keyDown(document.body, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(2);
  });
});

describe('media errors and recovery', () => {
  function networkError(p: ReturnType<typeof renderPlayer>) {
    mediaState(p.video()).error = { code: 2 };
    p.fire('error');
  }

  it('direct MEDIA_ERR_NETWORK with GET /api/media-files/{id} 404 → the gone screen, no re-request', async () => {
    server.use(http.get(`${API}/media-files/:id`, () => apiError(404, 'file_missing')));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    p.at(400);
    networkError(p);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Esta película no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.',
    );
    expect(p.rec.find('GET', '/api/media-files/91')).toHaveLength(1);
    expect(p.posts()).toHaveLength(1);
  });

  it('direct MEDIA_ERR_NETWORK with the file present → one re-request at the current time', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    p.at(400);
    networkError(p);
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 400, previous_session_id: null });
    await p.startDirect(400);
    expect(p.posts()).toHaveLength(2);
  });

  it('HLS recovery answered 404 file_missing → the gone screen', async () => {
    answerPlayback(f.playbackHls(), apiError(404, 'file_missing'));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.at(400);
    FakeHls.last?.fatal();
    expect(await screen.findByRole('alert')).toHaveTextContent('Falta el archivo de vídeo en el disco (¿está conectado el disco?).');
    expect(p.posts()).toHaveLength(2);
  });

  it('time-windowed: two fatal errors 40 s apart both recover; a third 10 s later shows the error with [Reintentar]', async () => {
    useFakePlayerTimers();
    answerPlayback(f.playbackHls(), hls2(), hls3());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(100);
    p.at(400);
    FakeHls.last?.fatal();
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startHls(400, 2);
    await advance(40_000);
    p.at(440);
    FakeHls.last?.fatal();
    await until(() => expect(p.posts()).toHaveLength(3));
    await p.startHls(440, 3);
    await advance(10_000);
    p.at(450);
    FakeHls.last?.fatal();
    await until(() => expect(screen.getByRole('alert')).toHaveTextContent('Error de reproducción: No se puede conectar con el servidor.'));
    expect(p.posts()).toHaveLength(3);
    expect((await p.postBodies()).map((b) => b.start_position_seconds)).toEqual([100, 400, 440]);
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(4));
    expect((await p.postBodies())[3]).toMatchObject({ start_position_seconds: 450 });
  });
});
