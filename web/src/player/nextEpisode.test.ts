import { QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { EpisodeDetail } from '@/api/types';
import * as f from '../../tests/fixtures';
import { tick, until } from '../../tests/flush';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { resolveNextEpisode } from './nextEpisode';

// Friends.S01E01E02.mkv (file 501) is linked to E01 (101) and E02 (102); E03 (103) has file 502.
const friendsFile = f.episodeFile({ id: 501, file_name: 'Friends.S01E01E02.mkv' });
const e01 = f.episodeDetail({ id: 101, episode_number: 1, media_files: [friendsFile], next_episode_id: 102 });
const e02 = f.episodeDetail({ id: 102, episode_number: 2, media_files: [friendsFile], next_episode_id: 103 });
const e03 = f.episodeDetail({ id: 103, episode_number: 3, media_files: [f.episodeFile({ id: 502 })], next_episode_id: null });

let rec: ReturnType<typeof recordRequests>;
const episodeGets = () => rec.find('GET', /^\/api\/episodes\//);

function serve(episodes: EpisodeDetail[]) {
  const byId = new Map(episodes.map((e) => [e.id, e]));
  server.use(
    http.get('/api/episodes/:id', ({ params }) => {
      const ep = byId.get(Number(params.id));
      return ep ? HttpResponse.json(ep) : apiError(404, 'not_found');
    }),
  );
}

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

beforeEach(() => {
  rec = recordRequests();
});
afterEach(() => rec.dispose());

describe('resolveNextEpisode (D12)', () => {
  it('skips an episode that contains the file that just played', async () => {
    serve([e02, e03]);
    const next = await resolveNextEpisode(client(), e01, 501);
    expect(next?.id).toBe(103);
    expect(episodeGets().map((c) => c.url.pathname)).toEqual(['/api/episodes/102', '/api/episodes/103']);
  });

  it('returns the direct next episode when it is another file', async () => {
    serve([e03]);
    const next = await resolveNextEpisode(client(), { next_episode_id: 103 }, 501);
    expect(next?.id).toBe(103);
  });

  it('returns null when the skipped episode has no next one', async () => {
    serve([{ ...e02, next_episode_id: null }]);
    await expect(resolveNextEpisode(client(), e01, 501)).resolves.toBeNull();
  });

  it('returns null without a request for the last episode', async () => {
    await expect(resolveNextEpisode(client(), { next_episode_id: null }, 501)).resolves.toBeNull();
    expect(episodeGets()).toHaveLength(0);
  });

  it('stops after 10 GETs of a chain sharing the file', async () => {
    const chain = Array.from({ length: 11 }, (_, i) =>
      f.episodeDetail({ id: 200 + i, media_files: [friendsFile], next_episode_id: 201 + i }),
    );
    serve(chain);
    await expect(resolveNextEpisode(client(), { next_episode_id: 200 }, 501)).resolves.toBeNull();
    expect(episodeGets()).toHaveLength(10);
  });

  it('returns null on a 404 candidate', async () => {
    serve([]);
    await expect(resolveNextEpisode(client(), e01, 501)).resolves.toBeNull();
  });

  it('reuses a fresh cached episode', async () => {
    const qc = client();
    qc.setQueryData(queryKeys.episode(102), e02);
    serve([e03]);
    const next = await resolveNextEpisode(qc, e01, 501);
    expect(next?.id).toBe(103);
    expect(episodeGets().map((c) => c.url.pathname)).toEqual(['/api/episodes/103']);
    expect(qc.getQueryData(queryKeys.episode(103))).toEqual(e03);
  });

  it('abort cancels the pending GET and resolves null', async () => {
    server.use(http.get('/api/episodes/:id', () => new Promise<Response>(() => undefined)));
    const qc = client();
    const controller = new AbortController();
    const result = resolveNextEpisode(qc, e01, 501, { signal: controller.signal });
    await until(() => expect(episodeGets()).toHaveLength(1));
    expect(qc.getQueryState(queryKeys.episode(102))?.fetchStatus).toBe('fetching');
    controller.abort();
    await expect(result).resolves.toBeNull();
    expect(qc.getQueryState(queryKeys.episode(102))?.fetchStatus).toBe('idle');
    await tick();
    expect(episodeGets()).toHaveLength(1);
  });
});
