// PlayerPage: the re-entry rule (exit write-through + barrier) and the one-shot `?t=`
// across reloads. The MSW server here is stateful: PUT /api/progress updates the
// progress that GET /api/films|episodes/{id} answers, like the real server.
import { cleanup, fireEvent, screen } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { MediaFile, Progress } from '@/api/types';
import * as f from '../../tests/fixtures';
import { advance, tick, until } from '../../tests/flush';
import { API } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, useFakePlayerTimers } from '../../tests/player';
import { createTestQueryClient, currentUrl } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

/** A server that remembers the last reported progress of the item. */
function statefulServer({
  type,
  files,
  initial,
  putDelayMs = 0,
}: {
  type: 'film' | 'episode';
  files: MediaFile[];
  initial: Progress | null;
  putDelayMs?: number;
}) {
  let progress = initial;
  const detail = () =>
    type === 'film'
      ? f.filmDetail({ media_files: files, progress })
      : f.episodeDetail({ media_files: files, progress, next_episode_id: null });
  server.use(
    http.get(`${API}/films/:id`, () => HttpResponse.json(detail())),
    http.get(`${API}/episodes/:id`, () => HttpResponse.json(detail())),
    http.put(`${API}/progress`, async ({ request }) => {
      const body = (await request.json()) as { media_file_id: number; position_seconds: number; duration_seconds: number | null };
      if (putDelayMs) await delay(putDelayMs);
      progress = f.progress({
        media_file_id: body.media_file_id,
        position_seconds: body.position_seconds,
        duration_seconds: body.duration_seconds ?? 0,
        updated_at: new Date().toISOString(),
      });
      return HttpResponse.json(progress);
    }),
  );
  return { detail, progress: () => progress };
}

const back = () => fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);

describe('re-entry within 30 s', () => {
  it('an episode started from the home fallback: the second POST uses the final report’s 400, not 100', async () => {
    useFakePlayerTimers();
    const files = [f.episodeFile({ id: 501 })];
    statefulServer({ type: 'episode', files, initial: f.progress({ media_file_id: 501, position_seconds: 100, duration_seconds: 1320 }) });
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(
      queryKeys.home(),
      f.homeResponse({
        continue_watching: [f.homeEpisode({ media_files: files, progress: f.progress({ media_file_id: 501, position_seconds: 100 }) })],
      }),
    );
    const p = renderPlayer('/watch/episode/345', { queryClient });
    await p.startDirect(100);
    p.at(400);
    back();
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await advance(5_000);
    void p.router.navigate('/watch/episode/345');
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies()).map((b) => b.start_position_seconds)).toEqual([100, 400]);
  });

  it('a film whose ["film", id] is cached from its detail page: the second POST uses 400', async () => {
    useFakePlayerTimers();
    const files = [f.mediaFile()];
    const initial = f.progress({ media_file_id: 91, position_seconds: 100 });
    statefulServer({ type: 'film', files, initial });
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.film(12), f.filmDetail({ media_files: files, progress: initial }));
    const p = renderPlayer('/watch/film/12', { queryClient });
    await p.startDirect(100);
    p.at(400);
    back();
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    void p.router.navigate('/watch/film/12?file=91');
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies()).map((b) => b.start_position_seconds)).toEqual([100, 400]);
  });

  it('a final PUT answered 1 s late: the second POST waits for it, then uses 400', async () => {
    useFakePlayerTimers();
    const files = [f.mediaFile()];
    const initial = f.progress({ media_file_id: 91, position_seconds: 100 });
    statefulServer({ type: 'film', files, initial, putDelayMs: 1_000 });
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.film(12), f.filmDetail({ media_files: files, progress: initial }));
    const p = renderPlayer('/watch/film/12', { queryClient });
    await p.startDirect(100);
    p.at(400);
    back();
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    void p.router.navigate('/watch/film/12');
    await until(() => expect(screen.getByTestId('player-root')).toBeInTheDocument());
    for (let i = 0; i < 20; i += 1) await tick();
    expect(p.posts()).toHaveLength(1);
    await advance(1_000);
    await until(() => expect(p.posts()).toHaveLength(2));
    expect((await p.postBodies())[1]).toMatchObject({ start_position_seconds: 400 });
  });
});

describe('one-shot ?t= across reloads', () => {
  it('next-part URL ?file=<p2>&t=0 → ?file=<p2> without a second POST; play to 600; a reload of that URL resumes at 600', async () => {
    useFakePlayerTimers();
    const parts = [f.mediaFile({ id: 201, part_number: 1, duration_seconds: 3000 }), f.mediaFile({ id: 202, part_number: 2, duration_seconds: 3000 })];
    const srv = statefulServer({ type: 'film', files: parts, initial: f.progress({ media_file_id: 201, position_seconds: 2990, duration_seconds: 3000 }) });
    answerPlayback((id) => HttpResponse.json(f.playbackDirect({ url: `/api/media-files/${id}/stream`, duration_seconds: 3000 })));
    const p = renderPlayer('/watch/film/12?file=202&t=0');
    await until(() => expect(currentUrl(p.router)).toBe('/watch/film/12?file=202'));
    await p.startDirect(0);
    expect(p.posts()).toHaveLength(1);
    p.at(600);
    await advance(10_000);
    const url = currentUrl(p.router);
    cleanup(); // the page goes away (reload): final report sent
    await until(() => expect(srv.progress()).toMatchObject({ media_file_id: 202, position_seconds: 600 }));

    const reloaded = renderPlayer(url, { queryClient: createTestQueryClient() });
    await until(() => expect(reloaded.posts()).toHaveLength(1));
    expect(reloaded.posts()[0]?.url.pathname).toBe('/api/media-files/202/playback');
    expect((await reloaded.postBodies())[0]).toMatchObject({ start_position_seconds: 600 });
  });

  it('a multi-part film from its card (no ?file=): Part 1 plays to ended; a reload picks Part 2 at 0 and no Part 1 report carries 0', async () => {
    useFakePlayerTimers();
    const parts = [f.mediaFile({ id: 201, part_number: 1, duration_seconds: 3000 }), f.mediaFile({ id: 202, part_number: 2, duration_seconds: 3000 })];
    const srv = statefulServer({ type: 'film', files: parts, initial: null });
    answerPlayback((id) => HttpResponse.json(f.playbackDirect({ url: `/api/media-files/${id}/stream`, duration_seconds: 3000 })));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.at(2990);
    p.at(3000, 'pause');
    p.at(3000, 'ended');
    await until(() => expect(screen.getByRole('dialog', { name: 'Siguiente parte' })).toBeInTheDocument());
    expect(currentUrl(p.router)).toBe('/watch/film/12');
    cleanup();
    await until(() => expect(p.puts().length).toBeGreaterThanOrEqual(2));

    await until(() => expect(srv.progress()?.position_seconds).toBe(3000));
    const reloaded = renderPlayer('/watch/film/12', { queryClient: createTestQueryClient() });
    await until(() => expect(reloaded.posts()).toHaveLength(1));
    expect(reloaded.posts()[0]?.url.pathname).toBe('/api/media-files/202/playback');
    expect((await reloaded.postBodies())[0]).toMatchObject({ start_position_seconds: 0 });
    const part1 = (await p.putBodies()).filter((b) => b.media_file_id === 201);
    expect(part1.some((b) => b.position_seconds === 0)).toBe(false);
  });
});
