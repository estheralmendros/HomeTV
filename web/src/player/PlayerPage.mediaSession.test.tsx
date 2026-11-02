// PlayerPage: Media Session handlers and metadata, so a Bluetooth headset's play/pause button and
// OS media keys reach the player.
import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { stubMediaSession, type FakeMediaSession } from '../../tests/mediaSession';
import { apiError } from '../../tests/msw/handlers';
import { answerPlayback, deferred, renderPlayer, serveEpisodes, useFakePlayerTimers } from '../../tests/player';
import { createTestQueryClient, currentUrl } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

let ms: FakeMediaSession;
beforeEach(() => {
  ms = stubMediaSession();
});
afterEach(() => ms.restore());

const ACTIONS = ['play', 'pause', 'seekbackward', 'seekforward'];

describe('Media Session', () => {
  it('pause/play act like the Pause/Play button (paused state + pause report), then resume', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    expect(ms.playbackState).toBe('playing');

    ms.trigger('pause');
    expect(pause).toHaveBeenCalledTimes(1);
    expect(p.video().paused).toBe(true);
    p.fire('pause'); // the element's own event, as after the Pause button
    expect(screen.getByRole('button', { name: 'Reproducir' })).toBeInTheDocument();
    expect(ms.playbackState).toBe('paused');
    await until(() => expect(p.puts()).toHaveLength(1));
    expect((await p.putBodies())[0]).toMatchObject({ position_seconds: 100, item_id: 12 });

    ms.trigger('play');
    expect(play).toHaveBeenCalledTimes(1);
    p.at(100, 'playing');
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
    expect(ms.playbackState).toBe('playing');
  });

  it('playbackState reaches "playing" after a no-op re-render while the element already plays (no stale effect deps)', async () => {
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src') ?? '').toMatch(/\/stream\?token=/));
    p.fire('loadedmetadata'); // attachSource seeks and calls play(): the element plays, the machine is still 'loading'
    // 'waiting' is a no-op while loading: React re-renders the page and bails out (same state)
    for (let i = 0; i < 3; i += 1) p.fire('waiting');
    expect(p.video().paused).toBe(false);
    p.at(100, 'playing');
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
    expect(ms.playbackState).toBe('playing');
  });

  it('seekforward / seekbackward move ±10 s like the controls', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    ms.trigger('seekforward');
    expect(p.video().currentTime).toBe(110);
    ms.trigger('seekbackward');
    ms.trigger('seekbackward');
    expect(p.video().currentTime).toBe(90);
  });

  it('film metadata: the player title and the poster artwork', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(ms.metadata?.title).toBe('Dune (2021)');
    expect(ms.metadata?.artwork[0]?.src).toMatch(/^\/api\/artwork\/w780\/dune-poster\.jpg\?token=/);
    expect(ms.calls.map(([action]) => action).sort()).toEqual([...ACTIONS].sort());
  });

  it('episode metadata: T2 · E3 · title by the show, with the still', async () => {
    const p = renderPlayer('/watch/episode/345');
    await p.startDirect(0);
    await until(() => expect(ms.metadata?.title).toBe('T2 · E3 · Office Olympics'));
    expect(ms.metadata?.artist).toBe('The Office');
    expect(ms.metadata?.artwork[0]?.src).toMatch(/^\/api\/artwork\/w780\/office-s2e3\.jpg\?token=/);
  });

  it('while 503 preparing the handlers do nothing: no play(), no extra POST', async () => {
    useFakePlayerTimers();
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    answerPlayback(apiError(503, 'preparing', { headers: { 'Retry-After': '5' } }), f.playbackDirect());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(screen.getByText(/Preparando el vídeo/)).toBeInTheDocument());
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    ms.trigger('play');
    ms.trigger('pause');
    ms.trigger('seekforward');
    ms.trigger('seekbackward');
    await advance(4_999);
    expect(play).not.toHaveBeenCalled();
    expect(pause).not.toHaveBeenCalled();
    expect(p.posts()).toHaveLength(1);
    expect(screen.getByText(/Preparando el vídeo/)).toBeInTheDocument();
  });

  it('blocked (autoplay rejected): play acts like "Toca para reproducir" and leaves blocked', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBeTruthy());
    p.fire('loadedmetadata');
    await until(() => expect(screen.getByRole('button', { name: 'Toca para reproducir' })).toBeInTheDocument());
    ms.trigger('play');
    expect(play).toHaveBeenCalledTimes(2);
    p.at(100, 'playing');
    expect(screen.queryByRole('button', { name: 'Toca para reproducir' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
    expect(ms.playbackState).toBe('playing');
  });

  it('next episode (PlayerPage remount): the old mount clears before the new one registers; 4 handlers, the new title', async () => {
    const ep = (id: number, n: number, fileId: number, next: number | null) =>
      f.episodeDetail({ id, episode_number: n, title: `Episodio ${n}`, media_files: [f.episodeFile({ id: fileId, duration_seconds: 1320 })], next_episode_id: next, progress: null });
    serveEpisodes([ep(345, 3, 501, 346), ep(346, 4, 502, null)]);
    answerPlayback((fileId) => f.playbackDirect({ url: `/api/media-files/${fileId}/stream`, duration_seconds: 1320 }));
    const p = renderPlayer('/watch/episode/345');
    await p.startDirect(0, /\/501\/stream/);
    await until(() => expect(ms.metadata?.title).toBe('T2 · E3 · Episodio 3'));
    p.at(1310);
    await until(() => expect(screen.getByRole('button', { name: 'Reproducir ahora' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
    await p.startDirect(0, /\/502\/stream/);
    await until(() => expect(ms.metadata?.title).toBe('T2 · E4 · Episodio 4'));
    expect(ms.handlers.size).toBe(ACTIONS.length);
    // The clears of the first mount precede the second registration.
    const actions = ms.calls.map(([action, handler]) => `${action}:${handler ? 'set' : 'null'}`);
    expect(actions).toEqual([...ACTIONS.map((a) => `${a}:set`), ...ACTIONS.map((a) => `${a}:null`), ...ACTIONS.map((a) => `${a}:set`)]);
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    ms.trigger('pause');
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it('the item becomes unknown while mounted (home-fallback start, GET 404): the Now Playing metadata is cleared', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const qc = createTestQueryClient();
    const entry = f.homeEpisode({ id: 345, progress: f.progress({ media_file_id: 501, position_seconds: 300, duration_seconds: 1320 }) });
    qc.setQueryData(queryKeys.home(), f.homeResponse({ continue_watching: [entry] }));
    const hold = deferred();
    serveEpisodes([], { 345: hold.promise });
    answerPlayback(f.playbackDirect({ url: '/api/media-files/501/stream', duration_seconds: 1320 }));
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    await p.startDirect(300);
    await until(() => expect(ms.metadata?.title).toBe('T2 · E3 · Office Olympics'));
    hold.resolve();
    await until(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(ms.metadata).toBeNull();
  });

  it('unmounting clears the handlers, the metadata and the playback state', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(ms.handlers.size).toBe(ACTIONS.length);
    p.unmount();
    expect(ms.handlers.size).toBe(0);
    for (const action of ACTIONS) expect(ms.calls).toContainEqual([action, null]);
    expect(ms.metadata).toBeNull();
    expect(ms.playbackState).toBe('none');
  });

  it('a MediaPlayPause keydown does nothing: the Media Session handlers are the only path (no double toggle)', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    fireEvent.keyDown(document.body, { key: 'MediaPlayPause' });
    fireEvent.keyDown(document.body, { key: 'MediaPause' });
    expect(pause).not.toHaveBeenCalled();
  });
});

describe('without the Media Session API', () => {
  it('MediaPlayPause / MediaPause / MediaPlay keydown events pause and resume (fallback)', async () => {
    ms.restore();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    fireEvent.keyDown(document.body, { key: 'MediaPlayPause' });
    expect(pause).toHaveBeenCalledTimes(1);
    p.fire('pause');
    fireEvent.keyDown(document.body, { key: 'MediaPlay' });
    expect(play).toHaveBeenCalledTimes(1);
    p.at(100, 'playing');
    fireEvent.keyDown(document.body, { key: 'MediaPause' });
    expect(pause).toHaveBeenCalledTimes(2);
  });

  it('plays normally (feature-detected)', async () => {
    ms.restore();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
  });
});
