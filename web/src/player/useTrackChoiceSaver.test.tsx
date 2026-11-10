import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { EpisodeDetail, FilmDetail, HomeResponse, TrackChoice } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import * as f from '../../tests/fixtures';
import { until } from '../../tests/flush';
import { recordRequests } from '../../tests/msw/server';
import { createTestQueryClient, renderWithProviders } from '../../tests/renderWithProviders';
import { deferred, gateTrackChoicePuts, renderPlayer, serveEpisodes } from '../../tests/player';
import { pendingChoice, type WriteResult } from './trackChoiceSession';
import { patchTrackChoice, useTrackChoiceSaver } from './useTrackChoiceSaver';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

/** Every enqueueWrite settle (a wish that is never sent still resolves: 'dropped'). */
const writes = vi.hoisted(() => [] as Array<Promise<WriteResult>>);
vi.mock('./trackChoiceSession', async (importOriginal) => {
  const real = await importOriginal<typeof import('./trackChoiceSession')>();
  return {
    ...real,
    enqueueWrite: (...args: Parameters<typeof real.enqueueWrite>) => {
      const result = real.enqueueWrite(...args);
      writes.push(result);
      return result;
    },
  };
});

beforeEach(() => {
  writes.length = 0;
});

/** Waits for every wish made so far to settle: no PUT can be sent after this. */
async function writesSettled(): Promise<WriteResult['outcome'][]> {
  const results = await Promise.all(writes.splice(0));
  return results.map((r) => r.outcome);
}

const ENG = { language: 'eng', title: null, channels: 2 };
const held: TrackChoice = { audio: null, subtitles: f.subtitleChoice() };

/** A pick button for show 7 (source track_choice = `held`) plus profile switch / sign-out buttons. */
function Harness({ profileId = 1 }: { profileId?: number }) {
  const { signIn, signOut, session } = useAuth();
  const saver = useTrackChoiceSaver({ kind: 'show', id: 7, profileId: session?.profile_id ?? profileId, source: { track_choice: held } });
  return (
    <>
      <button type="button" onClick={() => saver.saveAudio(ENG)}>
        pick
      </button>
      <button type="button" onClick={() => signIn(f.sessionResponse(f.profile({ id: 2, name: 'Kids' })))}>
        kids
      </button>
      <button type="button" onClick={signOut}>
        out
      </button>
    </>
  );
}

describe('useTrackChoiceSaver: profile safety (W-AC-19, W-AC-8c)', () => {
  it('after a reload (seeded session, no signIn) a pick sends its PUT: the held part kept, the new part set', async () => {
    const rec = recordRequests();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'pick' }));
    await until(() => expect(rec.find('PUT', '/api/track-choices/shows/7')).toHaveLength(1));
    await rec.settled();
    const put = rec.find('PUT', '/api/track-choices/shows/7')[0];
    expect(put?.body).toEqual({ audio: ENG, subtitles: held.subtitles });
    expect(put?.headers.get('authorization')).toBe('Bearer token-1');
  });

  it('a wish made while the getter answers another profile is never sent', async () => {
    const rec = recordRequests();
    const { user } = renderWithProviders(<Harness profileId={5} />, { path: '/', session: null });
    await user.click(screen.getByRole('button', { name: 'pick' }));
    expect(await writesSettled()).toEqual(['dropped']);
    await rec.settled();
    expect(rec.find('PUT', /track-choices/)).toHaveLength(0);
  });

  it('a pick queued behind a gated PUT under profile 1 is never sent after switching to profile 2; no override survives', async () => {
    const rec = recordRequests();
    const gate = gateTrackChoicePuts();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'pick' }));
    await user.click(screen.getByRole('button', { name: 'pick' }));
    await until(() => expect(gate.pending()).toBe(1));
    await user.click(screen.getByRole('button', { name: 'kids' }));
    expect(pendingChoice(1, 'show', 7)).toBeUndefined();
    expect(pendingChoice(2, 'show', 7)).toBeUndefined();
    gate.release();
    // The in-flight PUT settles after the switch ('dropped'); the queued pick was dropped unsent.
    expect(await writesSettled()).toEqual(['dropped', 'dropped']);
    await rec.settled();
    const sent = rec.find('PUT', /track-choices/);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers.get('authorization')).toBe('Bearer token-1');
  });

  it('sign-out clears every override', async () => {
    gateTrackChoicePuts();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'pick' }));
    expect(pendingChoice(1, 'show', 7)).toEqual({ audio: ENG, subtitles: held.subtitles });
    await user.click(screen.getByRole('button', { name: 'out' }));
    expect(pendingChoice(1, 'show', 7)).toBeUndefined();
  });
});

describe('test server: saved choices', () => {
  it('the factories stay pure after a save; the handlers answer the saved choice', async () => {
    f.saveTrackChoice('film', 12, held);
    expect(f.filmDetail().track_choice).toBeNull();
    expect(f.episodeDetail().track_choice).toBeNull();
    expect(f.homeEpisode().track_choice).toBeNull();
    const film = (await (await fetch(`${location.origin}/api/films/12`)).json()) as FilmDetail;
    expect(film.track_choice).toEqual(held);
  });
});

describe('patchTrackChoice keeps every entry’s freshness (W-AC-20)', () => {
  const next: TrackChoice = { audio: ENG, subtitles: null };
  const STALE = 1_000;

  function seed() {
    const qc = createTestQueryClient();
    const stale = f.episodeDetail({ id: 345, progress: f.progress({ media_file_id: 501, position_seconds: 100 }) });
    const otherShow = f.episodeDetail({ id: 900, show_id: 8 });
    const film = f.filmDetail();
    const home = f.homeResponse({
      continue_watching: [
        f.homeEpisode({ id: 345, progress: f.progress({ media_file_id: 501, position_seconds: 300, duration_seconds: 1320 }) }),
      ],
    });
    qc.setQueryData(queryKeys.episode(345), stale, { updatedAt: STALE });
    qc.setQueryData(queryKeys.episode(900), otherShow, { updatedAt: STALE });
    qc.setQueryData(queryKeys.film(12), film, { updatedAt: STALE });
    qc.setQueryData(queryKeys.home(), home);
    return { qc, otherShow, film };
  }

  it('writes only the show’s entries, never their dataUpdatedAt', () => {
    const { qc, otherShow, film } = seed();
    const homeAt = qc.getQueryState(queryKeys.home())?.dataUpdatedAt;
    patchTrackChoice(qc, 'show', 7, next);
    expect(qc.getQueryData<EpisodeDetail>(queryKeys.episode(345))?.track_choice).toEqual(next);
    expect(qc.getQueryData<HomeResponse>(queryKeys.home())?.continue_watching[0]).toMatchObject({ track_choice: next });
    expect(qc.getQueryState(queryKeys.episode(345))?.dataUpdatedAt).toBe(STALE);
    expect(qc.getQueryState(queryKeys.home())?.dataUpdatedAt).toBe(homeAt);
    expect(qc.getQueryData(queryKeys.episode(900))).toBe(otherShow);
    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))).toBe(film);
  });

  it('an invalidated entry stays invalidated; a film patch writes only the film', () => {
    const { qc } = seed();
    void qc.invalidateQueries({ queryKey: queryKeys.film(12), refetchType: 'none' });
    patchTrackChoice(qc, 'film', 12, next);
    expect(qc.getQueryData<FilmDetail>(queryKeys.film(12))?.track_choice).toEqual(next);
    expect(qc.getQueryState(queryKeys.film(12))?.isInvalidated).toBe(true);
    expect(qc.getQueryState(queryKeys.film(12))?.dataUpdatedAt).toBe(STALE);
    expect(qc.getQueryData<EpisodeDetail>(queryKeys.episode(345))?.track_choice).toBeNull();
  });

  it('after the patch the "Seguir viendo" start still resumes at Home’s position', async () => {
    const { qc } = seed();
    patchTrackChoice(qc, 'show', 7, next);
    const hold = deferred();
    serveEpisodes([f.episodeDetail({ id: 345 })], { 345: hold.promise });
    const p = renderPlayer('/watch/episode/345', { queryClient: qc });
    await until(() => expect(p.posts()).toHaveLength(1));
    expect((await p.postBodies())[0]).toMatchObject({ start_position_seconds: 300 });
    hold.resolve();
  });
});
