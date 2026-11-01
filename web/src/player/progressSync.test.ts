import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { EpisodeDetail, FilmDetail, HomeResponse, Progress, ProgressReport } from '@/api/types';
import * as f from '../../tests/fixtures';
import {
  awaitFinalBarrier,
  findHomeEpisode,
  hasPendingFinal,
  registerFinalReport,
  resetProgressSync,
  writeProgress,
} from './progressSync';

const film = { type: 'film', id: 12 } as const;
const episode = { type: 'episode', id: 345, showId: 7, seasonNumber: 2 } as const;
const fresh = f.progress({ position_seconds: 400, media_file_id: 91, updated_at: '2026-10-22T10:00:00Z' });
/** The final PUT's answer for `fresh` (no prompt). */
const freshReport = f.progressReport(fresh);

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

afterEach(() => resetProgressSync());

describe('writeProgress', () => {
  it('patches cached film/episode details and the matching home entries', () => {
    const qc = client();
    qc.setQueryData(queryKeys.film(12), f.filmDetail({ progress: f.progress({ position_seconds: 100 }) }));
    qc.setQueryData(queryKeys.episode(345), f.episodeDetail());
    const home = f.homeResponse({
      // 345 is a Next Up episode (no progress) inside Continue Watching (prd.md Rev 13).
      continue_watching: [f.filmSummary({ id: 12 }), f.homeEpisode({ id: 345, progress: null }), f.homeEpisode({ id: 999 })],
    });
    qc.setQueryData(queryKeys.home(), home);

    writeProgress(qc, film, fresh);
    writeProgress(qc, episode, fresh);

    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress).toEqual(fresh);
    expect(qc.getQueryData<EpisodeDetail>(queryKeys.episode(345))?.progress).toEqual(fresh);
    const patched = qc.getQueryData<HomeResponse>(queryKeys.home());
    expect(patched?.continue_watching.map((x) => x.progress)).toEqual([fresh, fresh, home.continue_watching[2]?.progress]);
    expect(patched).not.toHaveProperty('next_up');
  });

  it('creates no entry when nothing is cached', () => {
    const qc = client();
    writeProgress(qc, film, fresh);
    writeProgress(qc, episode, fresh);
    expect(qc.getQueryData(queryKeys.film(12))).toBeUndefined();
    expect(qc.getQueryData(queryKeys.episode(345))).toBeUndefined();
    expect(qc.getQueryData(queryKeys.home())).toBeUndefined();
  });

  it('does not touch a home film entry with the same id as an episode', () => {
    const qc = client();
    const home = f.homeResponse({ continue_watching: [f.filmSummary({ id: 345 })] });
    qc.setQueryData(queryKeys.home(), home);
    writeProgress(qc, episode, fresh);
    expect(qc.getQueryData<HomeResponse>(queryKeys.home())?.continue_watching[0]?.progress).toEqual(
      home.continue_watching[0]?.progress,
    );
  });
});

describe('registerFinalReport', () => {
  it('writes the 200 body before invalidating', async () => {
    const qc = client();
    qc.setQueryData(queryKeys.film(12), f.filmDetail());
    const seen: Array<Progress | null | undefined> = [];
    const invalidate = vi.spyOn(qc, 'invalidateQueries').mockImplementation(async () => {
      seen.push(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress);
    });

    await registerFinalReport(qc, film, Promise.resolve(freshReport));

    expect(invalidate).toHaveBeenCalled();
    expect(seen.length).toBeGreaterThan(0);
    for (const p of seen) expect(p).toEqual(fresh);
    const keys = invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toEqual(expect.arrayContaining([queryKeys.home(), queryKeys.film(12), ['films', 'list'], ['search']]));
  });

  it('writes only the Progress keys and hands a rating_prompt to onPrompt after the write', async () => {
    const qc = client();
    qc.setQueryData(queryKeys.film(12), f.filmDetail());
    const prompt = f.ratingPrompt();
    const onPrompt = vi.fn(() => {
      expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress).toEqual(fresh);
    });
    await registerFinalReport(qc, film, Promise.resolve(f.progressReport({ ...fresh, rating_prompt: prompt })), onPrompt);
    expect(onPrompt).toHaveBeenCalledWith(prompt);
    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress).toEqual(fresh);
    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress).not.toHaveProperty('rating_prompt');
  });

  it('no onPrompt call without a prompt or when the PUT fails', async () => {
    const onPrompt = vi.fn();
    await registerFinalReport(client(), film, Promise.resolve(f.progressReport(fresh)), onPrompt);
    await registerFinalReport(client(), film, Promise.reject(new Error('offline')), onPrompt);
    expect(onPrompt).not.toHaveBeenCalled();
  });

  it('a failed PUT only invalidates', async () => {
    const qc = client();
    const old = f.filmDetail();
    qc.setQueryData(queryKeys.film(12), old);
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    await registerFinalReport(qc, film, Promise.reject(new Error('offline')));
    expect(qc.getQueryData(queryKeys.film(12))).toBe(old);
    expect(invalidate).toHaveBeenCalled();
  });

  it('invalidates the episode, its show and season', async () => {
    const qc = client();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    await registerFinalReport(qc, episode, Promise.resolve(freshReport));
    const keys = invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toEqual(
      expect.arrayContaining([queryKeys.episode(345), queryKeys.show(7), queryKeys.season(7, 2), ['shows', 'list']]),
    );
  });

  it('is pending until the PUT settles', async () => {
    const qc = client();
    const put = deferred<ProgressReport>();
    const done = registerFinalReport(qc, film, put.promise);
    expect(hasPendingFinal(film)).toBe(true);
    put.resolve(freshReport);
    await done;
    expect(hasPendingFinal(film)).toBe(false);
  });
});

describe('awaitFinalBarrier', () => {
  it('returns at once when nothing is pending', async () => {
    await expect(awaitFinalBarrier(film)).resolves.toBe('none');
  });

  it('resolves when the pending PUT settles (written first)', async () => {
    vi.useFakeTimers();
    const qc = client();
    qc.setQueryData(queryKeys.film(12), f.filmDetail());
    const put = deferred<ProgressReport>();
    void registerFinalReport(qc, film, put.promise);
    const barrier = awaitFinalBarrier(film);
    let outcome: string | null = null;
    void barrier.then((r) => (outcome = r));
    await vi.advanceTimersByTimeAsync(1_000);
    expect(outcome).toBeNull();
    put.resolve(freshReport);
    await vi.advanceTimersByTimeAsync(0);
    expect(outcome).toBe('settled');
    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.progress).toEqual(fresh);
  });

  it('gives up after 3 s', async () => {
    vi.useFakeTimers();
    void registerFinalReport(client(), film, new Promise<ProgressReport>(() => undefined));
    let outcome: string | null = null;
    void awaitFinalBarrier(film).then((r) => (outcome = r));
    await vi.advanceTimersByTimeAsync(2_999);
    expect(outcome).toBeNull();
    await vi.advanceTimersByTimeAsync(1);
    expect(outcome).toBe('timeout');
  });

  it('is keyed per item', async () => {
    void registerFinalReport(client(), film, new Promise<ProgressReport>(() => undefined));
    await expect(awaitFinalBarrier({ type: 'episode', id: 12 })).resolves.toBe('none');
  });
});

describe('findHomeEpisode', () => {
  it('finds a Continue Watching episode (in progress or Next Up), never a film', () => {
    const home = f.homeResponse({
      continue_watching: [f.filmSummary({ id: 345 }), f.homeEpisode({ id: 1 }), f.homeEpisode({ id: 345, progress: null })],
    });
    expect(findHomeEpisode(home, 345)).toBe(home.continue_watching[2]);
    expect(findHomeEpisode(home, 1)).toBe(home.continue_watching[1]);
    expect(findHomeEpisode(home, 2)).toBeNull();
    expect(findHomeEpisode(undefined, 1)).toBeNull();
  });
});
