// PlayerPage: next episode / next part (prd2 §9 step 10, D12) and the same-route navigation
// guarantees of the stable shell (Player identity, Rev 5): remount, subtitles of the new file,
// fullscreen and the <video> node kept, old source released before the new one attaches.
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EpisodeDetail, MediaFile } from '@/api/types';
import { setCapabilityEnv } from './capabilities';
import { iphoneEnv } from '../../tests/capsEnvs';
import { FakeHls, hlsLog } from '../../tests/fakeHls';
import * as f from '../../tests/fixtures';
import { advance, blobText, until } from '../../tests/flush';
import { API } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, serveEpisodes, stubFullscreen, useFakePlayerTimers, type PlayerHarness } from '../../tests/player';
import { currentUrl } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const DURATION = 1320;

function episode(id: number, n: number, file: MediaFile, next: number | null): EpisodeDetail {
  return f.episodeDetail({
    id,
    season_number: 1,
    episode_number: n,
    title: `Episodio piloto ${n}`,
    media_files: [file],
    next_episode_id: next,
    progress: null,
  });
}

const epFile = (id: number) =>
  f.episodeFile({ id, duration_seconds: DURATION, subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, id)] });

/** The playback answer of a file: HLS session `sess-<fileId>`, its own subtitle URL. */
function hlsFor(fileId: number) {
  return HttpResponse.json(
    f.playbackHls({
      session_id: `sess-${fileId}`,
      url: `/api/hls/sess-${fileId}/index.m3u8`,
      duration_seconds: DURATION,
      subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, fileId)],
    }),
  );
}

let restoreFullscreen: (() => void) | null = null;
beforeEach(() => {
  server.use(
    http.get('/api/media-files/:id/subtitles/:track', ({ params }) =>
      new HttpResponse(`WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nArchivo ${String(params.id)}\n`, { headers: { 'Content-Type': 'text/vtt' } }),
    ),
  );
});
afterEach(() => {
  restoreFullscreen?.();
  restoreFullscreen = null;
});

/** The element reaches the end: `pause` then `ended`, as browsers fire them. */
function playToEnd(p: PlayerHarness) {
  p.at(DURATION - 25);
  p.at(DURATION, 'pause');
  p.at(DURATION, 'ended');
}

describe('next episode', () => {
  beforeEach(() => {
    serveEpisodes([episode(345, 1, epFile(501), 346), episode(346, 2, epFile(502), 347), episode(347, 3, epFile(503), null)]);
    answerPlayback((fileId) => hlsFor(fileId));
  });

  it('the prompt appears in the last 30 s, counts down 10 s after ended and replaces the URL with the next episode', async () => {
    useFakePlayerTimers();
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 31);
    await advance(0);
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    p.at(DURATION - 30);
    await until(() => expect(screen.getByRole('dialog', { name: 'Siguiente episodio' })).toHaveTextContent('A continuación: T1 · E2'));
    expect(screen.queryByText(/Se reproducirá en/)).not.toBeInTheDocument();
    p.at(DURATION, 'ended');
    await until(() => expect(screen.getByText('Se reproducirá en 10 s')).toBeInTheDocument());
    await advance(3_000);
    expect(screen.getByText('Se reproducirá en 7 s')).toBeInTheDocument();
    await advance(7_000);
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
    expect(p.router.state.historyAction).toBe('REPLACE');
    await until(() => expect(p.posts().map((c) => c.url.pathname)).toEqual(['/api/media-files/501/playback', '/api/media-files/502/playback']));
  });

  it('[Reproducir ahora] and N start the next episode at once; Cancel hides the prompt and ended then offers Volver / Volver a reproducir', async () => {
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 10);
    await screen.findByRole('dialog', { name: 'Siguiente episodio' });
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    p.at(DURATION, 'ended');
    const end = await screen.findByRole('dialog', { name: 'Fin de la reproducción' });
    expect(end).toHaveTextContent('Volver a reproducir');
    expect(end).toHaveTextContent('Volver');
    expect(currentUrl(p.router)).toBe('/watch/episode/345');
  });

  it('[Reproducir ahora] navigates immediately', async () => {
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 10);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
  });

  it('N plays the next episode while its prompt is visible', async () => {
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    fireEvent.keyDown(document.body, { key: 'n' });
    expect(currentUrl(p.router)).toBe('/watch/episode/345');
    p.at(DURATION - 10);
    await screen.findByRole('dialog', { name: 'Siguiente episodio' });
    fireEvent.keyDown(document.body, { key: 'n' });
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
  });

  it('the last episode: ended shows Volver / Volver a reproducir, no prompt; Volver a reproducir restarts at 0', async () => {
    const p = renderPlayer('/watch/episode/347');
    await p.startHls(0);
    p.at(DURATION - 10);
    p.at(DURATION, 'ended');
    const end = await screen.findByRole('dialog', { name: 'Fin de la reproducción' });
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Volver a reproducir' }));
    expect(p.video().currentTime).toBe(0);
    expect(end).not.toBeInTheDocument();
  });
});

describe('D12: multi-episode files', () => {
  const shared = f.episodeFile({ id: 501, file_name: 'Friends.S01E01E02.mkv', duration_seconds: DURATION });

  it('E01 played from Friends.S01E01E02.mkv offers and auto-advances to E03, never E02', async () => {
    useFakePlayerTimers();
    serveEpisodes([episode(345, 1, shared, 346), episode(346, 2, shared, 347), episode(347, 3, epFile(503), null)]);
    answerPlayback((fileId) => hlsFor(fileId));
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 20);
    await until(() => expect(screen.getByRole('dialog', { name: 'Siguiente episodio' })).toHaveTextContent('A continuación: T1 · E3'));
    p.at(DURATION, 'ended');
    await advance(10_000);
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/347'));
  });

  it('nothing after E02: ended shows Volver / Volver a reproducir with no prompt or countdown', async () => {
    serveEpisodes([episode(345, 1, shared, 346), episode(346, 2, shared, null)]);
    answerPlayback((fileId) => hlsFor(fileId));
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    p.at(DURATION - 20);
    p.at(DURATION, 'ended');
    expect(await screen.findByRole('dialog', { name: 'Fin de la reproducción' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Se reproducirá en/)).not.toBeInTheDocument();
    expect(p.rec.find('GET', '/api/episodes/346')).toHaveLength(1);
  });
});

describe('next part', () => {
  const parts = [
    f.mediaFile({ id: 201, part_number: 1, duration_seconds: DURATION, subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, 201)] }),
    f.mediaFile({ id: 202, part_number: 2, duration_seconds: DURATION, subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, 202)] }),
  ];
  beforeEach(() => {
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ title: 'Kill Bill: Vol. 1', year: 2003, media_files: parts }))));
    answerPlayback((fileId) => hlsFor(fileId));
  });

  it('"Parte 1 terminada" only on ended, no countdown; [Reproducir parte 2] → ?file=<part2>&t=0 (t then dropped), Part 2 at 0', async () => {
    useFakePlayerTimers();
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    p.at(DURATION - 5);
    await advance(0);
    expect(screen.queryByRole('dialog', { name: 'Siguiente parte' })).not.toBeInTheDocument();
    p.at(DURATION, 'ended');
    await until(() => expect(screen.getByRole('dialog', { name: 'Siguiente parte' })).toHaveTextContent('Parte 1 terminada'));
    await advance(30_000);
    expect(currentUrl(p.router)).toBe('/watch/film/12');
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir parte 2' }));
    await until(() => expect(p.posts()).toHaveLength(2));
    expect(p.posts()[1]?.url.pathname).toBe('/api/media-files/202/playback');
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 0 });
    await until(() => expect(currentUrl(p.router)).toBe('/watch/film/12?file=202'));
    expect(screen.getByRole('heading', { level: 1, name: 'Kill Bill: Vol. 1 (2003) · Parte 2' })).toBeInTheDocument();
  });
});

describe('same-route navigation (Player identity)', () => {
  beforeEach(() => {
    serveEpisodes([episode(345, 1, epFile(501), 346), episode(346, 2, epFile(502), null)]);
    answerPlayback((fileId) => hlsFor(fileId));
  });

  it('next episode: remount (new POST, DELETE of the old session); the <track> shows the new file’s VTT and the old blob URL is revoked', async () => {
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(p.video().querySelector('track')).not.toBeNull());
    const oldBlob = p.video().querySelector('track')?.getAttribute('src');
    p.at(DURATION - 10);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(p.posts()).toHaveLength(2));
    await until(() => expect(p.deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-501']));
    await p.startHls(0, 2);
    await until(() => expect(p.rec.find('GET', '/api/media-files/502/subtitles/emb-3.vtt')).toHaveLength(1));
    await until(() => expect(p.video().querySelector('track')?.getAttribute('src')).toBe('blob:http://localhost:3000/mock-2'));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(oldBlob);
    const created = vi.mocked(URL.createObjectURL).mock.calls.map(([blob]) => blob as Blob);
    expect(await blobText(created[1] as Blob)).toContain('Archivo 502');
    expect(p.video().querySelectorAll('track')).toHaveLength(1);
  });

  it('next part: remount (new POST, DELETE of the old session); the <track> shows Part 2’s VTT and Part 1’s blob URL is revoked', async () => {
    const parts = [
      f.mediaFile({ id: 201, part_number: 1, duration_seconds: DURATION, subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, 201)] }),
      f.mediaFile({ id: 202, part_number: 2, duration_seconds: DURATION, subtitle_tracks: [f.subtitleTrack({ id: 'emb-3' }, 202)] }),
    ];
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ media_files: parts }))));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(p.video().querySelector('track')).not.toBeNull());
    const oldBlob = p.video().querySelector('track')?.getAttribute('src');
    playToEnd(p);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir parte 2' }));
    await until(() => expect(p.posts().map((c) => c.url.pathname)).toEqual(['/api/media-files/201/playback', '/api/media-files/202/playback']));
    await until(() => expect(p.deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-201']));
    await p.startHls(0, 2);
    await until(() => expect(p.rec.find('GET', '/api/media-files/202/subtitles/emb-3.vtt')).toHaveLength(1));
    await until(() => expect(p.video().querySelector('track')?.getAttribute('src')).toBe('blob:http://localhost:3000/mock-2'));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(oldBlob);
    const created = vi.mocked(URL.createObjectURL).mock.calls.map(([blob]) => blob as Blob);
    expect(await blobText(created[1] as Blob)).toContain('Archivo 202');
  });

  it('fullscreen and the <video> node survive the countdown navigation; the old hls.js instance is destroyed before the new one attaches', async () => {
    useFakePlayerTimers();
    restoreFullscreen = stubFullscreen();
    const p = renderPlayer('/watch/episode/345');
    await p.startHls(0);
    const root = screen.getByTestId('player-root');
    const video = p.video();
    fireEvent.click(screen.getByRole('button', { name: 'Pantalla completa' }));
    expect(document.fullscreenElement).toBe(root);
    expect(screen.getByRole('button', { name: 'Salir de pantalla completa' })).toBeInTheDocument();
    p.at(DURATION - 10);
    p.at(DURATION, 'ended');
    await advance(10_000);
    await until(() => expect(currentUrl(p.router)).toBe('/watch/episode/346'));
    await until(() => expect(FakeHls.instances).toHaveLength(2));
    await until(() => expect(hlsLog).toContain('attachMedia#2'));
    expect(hlsLog.indexOf('destroy#1')).toBeGreaterThan(-1);
    expect(hlsLog.indexOf('destroy#1')).toBeLessThan(hlsLog.indexOf('attachMedia#2'));
    expect(document.fullscreenElement).toBe(root);
    expect(root.isConnected).toBe(true);
    expect(p.video()).toBe(video);
    expect(screen.getByRole('button', { name: 'Salir de pantalla completa' })).toBeInTheDocument();
  });

  it('iOS env: removeAttribute("src") + load() run before the new src is set', async () => {
    setCapabilityEnv(iphoneEnv());
    const p = renderPlayer('/watch/episode/345');
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/hls/sess-501/index.m3u8'));
    const video = p.video();
    const log: string[] = [];
    const srcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src') as PropertyDescriptor;
    Object.defineProperty(video, 'src', {
      configurable: true,
      get: () => srcDescriptor.get?.call(video),
      set: (value: string) => {
        log.push(`src=${value}`);
        srcDescriptor.set?.call(video, value);
      },
    });
    const removeAttribute = video.removeAttribute.bind(video);
    vi.spyOn(video, 'removeAttribute').mockImplementation((name: string) => {
      log.push(`removeAttribute(${name})`);
      removeAttribute(name);
    });
    const load = video.load.bind(video);
    vi.spyOn(video, 'load').mockImplementation(() => {
      log.push('load()');
      load();
    });
    p.fire('loadedmetadata');
    p.at(0, 'playing');
    p.at(DURATION - 10);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(log).toContain('src=/api/hls/sess-502/index.m3u8'));
    expect(log.slice(0, 3)).toEqual(['removeAttribute(src)', 'load()', 'src=/api/hls/sess-502/index.m3u8']);
    expect(p.video()).toBe(video);
  });

  it('next part keeps fullscreen and the <video> node too', async () => {
    restoreFullscreen = stubFullscreen();
    const parts = [f.mediaFile({ id: 201, part_number: 1, duration_seconds: DURATION }), f.mediaFile({ id: 202, part_number: 2, duration_seconds: DURATION })];
    server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ media_files: parts }))));
    const p = renderPlayer('/watch/film/12');
    await p.startHls(0);
    const root = screen.getByTestId('player-root');
    const video = p.video();
    fireEvent.click(screen.getByRole('button', { name: 'Pantalla completa' }));
    playToEnd(p);
    fireEvent.click(await screen.findByRole('button', { name: 'Reproducir parte 2' }));
    await until(() => expect(hlsLog).toContain('attachMedia#2'));
    expect(hlsLog.indexOf('destroy#1')).toBeLessThan(hlsLog.indexOf('attachMedia#2'));
    expect(document.fullscreenElement).toBe(root);
    expect(p.video()).toBe(video);
  });
});
