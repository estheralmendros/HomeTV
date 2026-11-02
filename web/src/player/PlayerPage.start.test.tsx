// PlayerPage: item load, start decision, attach paths, one-shot ?t=, different-version notice, home fallback
// and the Home → player flows flows.
import { act, fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { EpisodeDetail, MediaFile } from '@/api/types';
import { HomePage } from '@/features/home/HomePage';
import { getClientCaps, setCapabilityEnv } from './capabilities';
import { PlayerRoute } from './PlayerRoute';
import { iphoneEnv } from '../../tests/capsEnvs';
import { FakeHls } from '../../tests/fakeHls';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { mediaState } from '../../tests/media';
import { API } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { PLAYER_PATH, answerPlayback, deferred, renderPlayer, serveEpisodes, useFakePlayerTimers } from '../../tests/player';
import { currentUrl, renderWithProviders } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160 });

function serveFilm(film = f.filmDetail()) {
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film)));
}

describe('PlayerPage: attach paths', () => {
  it('direct mode: POST playback with the client caps, then <video src> with the token', async () => {
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.posts()).toHaveLength(1));
    expect(p.posts()[0]?.url.pathname).toBe('/api/media-files/91/playback');
    expect((await p.postBodies())[0]).toEqual({
      client_caps: getClientCaps(),
      audio_stream_index: null,
      start_position_seconds: 0,
      previous_session_id: null,
    });
    await p.startDirect(0);
    expect(p.video().getAttribute('src')).toBe('/api/media-files/91/stream?token=token-1');
    expect(p.video()).toHaveAttribute('playsinline');
    expect(p.video()).toHaveAttribute('preload', 'metadata');
    expect(screen.getByRole('heading', { level: 1, name: 'Dune (2021)' })).toBeInTheDocument();
    expect(document.title).toBe('Dune (2021) · HomeTV');
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
  });

  it('hls mode: Hls.loadSource with the untokenized URL and startPosition = the saved position', async () => {
    serveFilm(f.filmDetail({ progress: f.progress({ media_file_id: 91, position_seconds: 600 }) }));
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await p.startHls(600);
    expect(FakeHls.last?.url).toBe('/api/hls/sess-1/index.m3u8');
    expect(FakeHls.last?.config.startPosition).toBe(600);
    expect((await p.postBodies())[0]?.start_position_seconds).toBe(600);
    expect(p.video().hasAttribute('src')).toBe(false);
  });

  it('iOS env: native HLS through video.src (no token), hls.js never constructed', async () => {
    setCapabilityEnv(iphoneEnv());
    answerPlayback(f.playbackHls());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/hls/sess-1/index.m3u8'));
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('the default file comes from selectVersion (an HEVC first version is skipped in Chrome)', async () => {
    serveFilm(
      f.filmDetail({
        media_files: [f.mediaFile({ id: 90, video: HEVC, label: '2160p HEVC' }), f.mediaFile({ id: 91 })],
      }),
    );
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.posts()).toHaveLength(1));
    expect(p.posts()[0]?.url.pathname).toBe('/api/media-files/91/playback');
  });

  it('?file= names the version; a file of another item falls back to selectVersion', async () => {
    serveFilm(f.filmDetail({ media_files: [f.mediaFile({ id: 91 }), f.mediaFile({ id: 92 })] }));
    const p = renderPlayer('/watch/film/12?file=92');
    await until(() => expect(p.posts()).toHaveLength(1));
    expect(p.posts()[0]?.url.pathname).toBe('/api/media-files/92/playback');
    p.unmount();

    const q = renderPlayer('/watch/film/12?file=999');
    await until(() => expect(q.posts()).toHaveLength(1));
    expect(q.posts()[0]?.url.pathname).toBe('/api/media-files/91/playback');
  });

  it('an unknown item type renders NotFound', async () => {
    renderPlayer('/watch/song/12');
    expect(await screen.findByText('Página no encontrada')).toBeInTheDocument();
  });

  it('the title names the part of a multi-part film', async () => {
    serveFilm(
      f.filmDetail({
        title: 'Kill Bill: Vol. 1',
        year: 2003,
        media_files: [f.mediaFile({ id: 201, part_number: 1 }), f.mediaFile({ id: 202, part_number: 2 })],
      }),
    );
    renderPlayer('/watch/film/12?file=202');
    expect(await screen.findByRole('heading', { level: 1, name: 'Kill Bill: Vol. 1 (2003) · Parte 2' })).toBeInTheDocument();
  });
});

describe('PlayerPage under StrictMode (dev double effects)', () => {
  it('plays, and Back still sends one final report and the DELETE', async () => {
    answerPlayback(f.playbackHls());
    const rec = recordRequests();
    renderWithProviders(
      <StrictMode>
        <PlayerRoute />
      </StrictMode>,
      { path: PLAYER_PATH, route: '/watch/film/12', history: ['/'] },
    );
    const video = screen.getByTestId('player-root').querySelector('video') as HTMLVideoElement;
    await until(() => expect(FakeHls.last?.media).toBe(video));
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
    mediaState(video).currentTime = 0;
    act(() => void video.dispatchEvent(new Event('playing')));
    mediaState(video).currentTime = 400;
    act(() => void video.dispatchEvent(new Event('timeupdate')));
    fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);
    await until(() => expect(rec.find('DELETE', '/api/hls/sess-1')).toHaveLength(1));
    await until(() => expect(rec.find('PUT', '/api/progress')).toHaveLength(1));
    await rec.settled();
    expect(rec.find('PUT', '/api/progress')[0]?.body).toMatchObject({ position_seconds: 400 });
    expect(rec.find('POST', /\/playback$/)).toHaveLength(1);
  });
});

describe('PlayerPage: one-shot ?t=', () => {
  it('?t=0 overrides the saved position, then only t is dropped (replace, no remount, no second POST)', async () => {
    serveFilm(f.filmDetail({ progress: f.progress({ media_file_id: 91, position_seconds: 600 }) }));
    const p = renderPlayer('/watch/film/12?file=91&t=0');
    await until(() => expect(currentUrl(p.router)).toBe('/watch/film/12?file=91'));
    expect(p.router.state.historyAction).toBe('REPLACE');
    await p.startDirect(0);
    expect((await p.postBodies()).map((b) => b.start_position_seconds)).toEqual([0]);
    expect(p.rec.find('GET', '/api/films/12')).toHaveLength(1);
  });

  it('deep link with ?t=: after the replace, [Volver] goes to Home (no in-app history), not navigate(-1)', async () => {
    const p = renderPlayer('/watch/film/12?file=91&t=0', { history: [] });
    await until(() => expect(currentUrl(p.router)).toBe('/watch/film/12?file=91'));
    await p.startDirect(0);
    fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    expect(p.router.state.historyAction).toBe('REPLACE');
  });

  it('a start without t (Continue Watching) causes no navigation and never adds file', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(currentUrl(p.router)).toBe('/watch/film/12');
    expect(p.posts()).toHaveLength(1);
    expect(p.rec.find('GET', '/api/films/12')).toHaveLength(1);
  });

  it('a next-part URL with t keeps file and the other params, mounts once and POSTs once', async () => {
    serveFilm(f.filmDetail({ media_files: [f.mediaFile({ id: 201, part_number: 1 }), f.mediaFile({ id: 202, part_number: 2 })] }));
    const p = renderPlayer('/watch/film/12?file=202&t=0&x=1');
    await until(() => expect(currentUrl(p.router)).toBe('/watch/film/12?file=202&x=1'));
    await p.startDirect(0);
    expect(p.posts().map((c) => c.url.pathname)).toEqual(['/api/media-files/202/playback']);
    expect(p.rec.find('GET', '/api/films/12')).toHaveLength(1);
  });
});

describe('PlayerPage: different-version notice', () => {
  const files = (siblingDuration: number): MediaFile[] => [
    f.mediaFile({ id: 90, video: HEVC, duration_seconds: 7200 }),
    f.mediaFile({ id: 91, duration_seconds: siblingDuration }),
  ];
  const progress = f.progress({ media_file_id: 90, position_seconds: 600, duration_seconds: 7200 });
  const NOTICE = 'Reproduciendo otra versión desde el principio';

  it('a non-matching sibling starts at 0 and shows the notice once playback starts (4 s)', async () => {
    useFakePlayerTimers();
    serveFilm(f.filmDetail({ media_files: files(6600), progress }));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.posts()).toHaveLength(1));
    expect((await p.postBodies())[0]).toMatchObject({ start_position_seconds: 0 });
    expect(p.posts()[0]?.url.pathname).toBe('/api/media-files/91/playback');
    await until(() => expect(p.video().getAttribute('src')).toBeTruthy());
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    await p.startDirect(0);
    expect(screen.getByText(NOTICE)).toBeInTheDocument();
    await advance(4_000);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('a matching sibling resumes at the saved position without the notice', async () => {
    serveFilm(f.filmDetail({ media_files: files(7190), progress }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(600);
    expect((await p.postBodies())[0]).toMatchObject({ start_position_seconds: 600 });
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('never with ?t=', async () => {
    serveFilm(f.filmDetail({ media_files: files(6600), progress }));
    const p = renderPlayer('/watch/film/12?t=0');
    await p.startDirect(0);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });
});

describe('PlayerPage: home fallback', () => {
  const homeEntry = (overrides: Partial<EpisodeDetail> = {}) =>
    f.homeEpisode({ id: 345, progress: f.progress({ media_file_id: 501, position_seconds: 300, duration_seconds: 1320 }), ...overrides });

  it('POST playback before GET /api/episodes/{id} resolves; the detail is still fetched and never seeded from home; the prompt appears in the last 30 s', async () => {
    const qc = (await import('../../tests/renderWithProviders')).createTestQueryClient();
    qc.setQueryData(queryKeys.home(), f.homeResponse({ continue_watching: [homeEntry()] }));
    const hold = deferred();
    serveEpisodes(
      [
        f.episodeDetail({ id: 345, next_episode_id: 346 }),
        f.episodeDetail({ id: 346, episode_number: 4, title: 'The Fire', media_files: [f.episodeFile({ id: 502 })] }),
      ],
      { 345: hold.promise },
    );
    answerPlayback(f.playbackDirect({ url: '/api/media-files/501/stream', duration_seconds: 1320.4 }));
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    await until(() => expect(p.posts()).toHaveLength(1));
    expect(p.posts()[0]?.url.pathname).toBe('/api/media-files/501/playback');
    expect((await p.postBodies())[0]).toMatchObject({ start_position_seconds: 300 });
    expect(p.rec.find('GET', '/api/episodes/345')).toHaveLength(1);
    expect(qc.getQueryData(queryKeys.episode(345))).toBeUndefined();
    expect(screen.getByRole('heading', { level: 1, name: 'The Office · T2 · E3 · Office Olympics' })).toBeInTheDocument();

    await p.startDirect(300);
    p.at(1300);
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    hold.resolve();
    const prompt = await screen.findByRole('dialog', { name: 'Siguiente episodio' });
    expect(prompt).toHaveTextContent('A continuación: T2 · E4');
    expect(prompt).toHaveTextContent('The Fire');
    expect((qc.getQueryData(queryKeys.episode(345)) as EpisodeDetail).next_episode_id).toBe(346);
  });

  it('an episode whose file is gone: POST 404 file_missing → the gone screen after one POST', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const qc = (await import('../../tests/renderWithProviders')).createTestQueryClient();
    qc.setQueryData(queryKeys.home(), f.homeResponse({ continue_watching: [homeEntry()] }));
    answerPlayback(HttpResponse.json({ error: { code: 'file_missing', message: 'gone', details: {} } }, { status: 404 }));
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Este episodio no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.');
    expect(alert).toHaveTextContent('Falta el archivo de vídeo en el disco (¿está conectado el disco?).');
    expect(p.posts()).toHaveLength(1);
    fireEvent.click(within(alert).getByRole('button', { name: 'Volver' }));
    await until(() => expect(currentUrl(p.router)).toBe('/'));
  });
});

describe('Home → player', () => {
  function renderHomeAndPlayer(home: ReturnType<typeof f.homeResponse>) {
    server.use(http.get(`${API}/home`, () => HttpResponse.json(home)));
    const rec = recordRequests();
    const view = renderWithProviders(<HomePage />, { path: '/', route: '/', routes: [{ path: PLAYER_PATH, element: <PlayerRoute /> }] });
    return { ...view, rec };
  }

  const episodeFiles = (siblingDuration: number): MediaFile[] => [
    f.episodeFile({ id: 501, video: HEVC, label: '2160p HEVC', duration_seconds: 1320 }),
    f.episodeFile({ id: 502, duration_seconds: siblingDuration }),
  ];
  const entry = (siblingDuration: number) =>
    f.homeEpisode({
      media_files: episodeFiles(siblingDuration),
      progress: f.progress({ media_file_id: 501, position_seconds: 600, duration_seconds: 1320 }),
    });

  it('an HEVC-last-played episode starts its matching H.264 sibling at the saved position', async () => {
    serveEpisodes([f.episodeDetail({ media_files: episodeFiles(1318) })]);
    const { rec, user } = renderHomeAndPlayer(f.homeResponse({ continue_watching: [entry(1318)] }));
    await user.click(await screen.findByRole('link', { name: 'The Office · T2 · E3 · Office Olympics' }));
    await until(() => expect(rec.find('POST', /\/playback$/)).toHaveLength(1));
    await rec.settled();
    const post = rec.find('POST', /\/playback$/)[0];
    expect(post?.url.pathname).toBe('/api/media-files/502/playback');
    expect(post?.body).toMatchObject({ start_position_seconds: 600 });
  });

  it('a non-matching sibling starts at 0 with the "different version" notice', async () => {
    serveEpisodes([f.episodeDetail({ media_files: episodeFiles(1200) })]);
    const { rec, user } = renderHomeAndPlayer(f.homeResponse({ continue_watching: [entry(1200)] }));
    await user.click(await screen.findByRole('link', { name: 'The Office · T2 · E3 · Office Olympics' }));
    await until(() => expect(rec.find('POST', /\/playback$/)).toHaveLength(1));
    await rec.settled();
    expect(rec.find('POST', /\/playback$/)[0]?.url.pathname).toBe('/api/media-files/502/playback');
    expect(rec.find('POST', /\/playback$/)[0]?.body).toMatchObject({ start_position_seconds: 0 });
    const video = screen.getByTestId('player-root').querySelector('video') as HTMLVideoElement;
    await until(() => expect(video.getAttribute('src')).toBeTruthy());
    act(() => void video.dispatchEvent(new Event('playing')));
    expect(await screen.findByText('Reproduciendo otra versión desde el principio')).toBeInTheDocument();
  });

  it('the Continue Watching episode starts from the home media_files before GET /api/episodes/{id} resolves', async () => {
    const hold = deferred();
    serveEpisodes([f.episodeDetail({ media_files: episodeFiles(1318) })], { 345: hold.promise });
    const { rec, user } = renderHomeAndPlayer(f.homeResponse({ continue_watching: [entry(1318)] }));
    await user.click(await screen.findByRole('link', { name: 'The Office · T2 · E3 · Office Olympics' }));
    await until(() => expect(rec.find('POST', /\/playback$/)).toHaveLength(1));
    expect(rec.find('GET', '/api/episodes/345')).toHaveLength(1);
    hold.resolve();
  });

  it('a multi-part film card whose Part 1 was finished starts Part 2 at 0', async () => {
    const parts = [
      f.mediaFile({ id: 201, part_number: 1, duration_seconds: 3000 }),
      f.mediaFile({ id: 202, part_number: 2, duration_seconds: 3100 }),
    ];
    const progress = f.progress({ media_file_id: 201, position_seconds: 2990, duration_seconds: 3000 });
    serveFilm(f.filmDetail({ id: 20, title: 'Kill Bill: Vol. 1', year: 2003, media_files: parts, progress }));
    const { rec, user, router } = renderHomeAndPlayer(
      f.homeResponse({ continue_watching: [f.filmSummary({ id: 20, title: 'Kill Bill: Vol. 1', year: 2003, progress })] }),
    );
    await user.click(await screen.findByRole('link', { name: 'Kill Bill: Vol. 1 (2003)' }));
    await until(() => expect(rec.find('POST', /\/playback$/)).toHaveLength(1));
    await rec.settled();
    expect(rec.find('POST', /\/playback$/)[0]?.url.pathname).toBe('/api/media-files/202/playback');
    expect(rec.find('POST', /\/playback$/)[0]?.body).toMatchObject({ start_position_seconds: 0 });
    expect(currentUrl(router)).toBe('/watch/film/20');
  });
});
