import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { StrictMode, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setTokenGetter } from '@/api/client';
import { queryKeys, type PlayedItem } from '@/api/queryKeys';
import type { FilmDetail, RatingPrompt } from '@/api/types';
import * as f from '../../tests/fixtures';
import { tick, until } from '../../tests/flush';
import { mediaState } from '../../tests/media';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import type { PlayerStateName } from './playerMachine';
import { hasPendingFinal } from './progressSync';
import { PositionTracker, useProgressReporter, type ProgressReporterOptions } from './useProgressReporter';

const FILM: PlayedItem = { type: 'film', id: 12 };

let video: HTMLVideoElement;
let rec: ReturnType<typeof recordRequests>;
let queryClient: QueryClient;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  setTokenGetter(() => 'token-ana');
  video = document.createElement('video');
  rec = recordRequests();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(() => rec.dispose());

const puts = () => rec.find('PUT', '/api/progress');
async function bodies() {
  await rec.settled();
  return puts().map((c) => c.body as { position_seconds: number; duration_seconds: number | null; media_file_id: number });
}
const positions = async () => (await bodies()).map((b) => b.position_seconds);

/** A tracker attached and live at `position` (as after the start seek). */
function liveTracker(position = 100, duration: number | null = 9360) {
  const tracker = new PositionTracker(position, duration);
  const gen = tracker.beginAttach(position, { duration, resetPosition: false });
  mediaState(video).currentTime = position;
  tracker.handle(gen, 'playing', video);
  return { tracker, gen };
}

function at(tracker: PositionTracker, gen: number, seconds: number, type: 'timeupdate' | 'seeked' | 'pause' | 'ended' = 'timeupdate') {
  mediaState(video).currentTime = seconds;
  tracker.handle(gen, type, video);
}

type Props = Pick<ProgressReporterOptions, 'stateName' | 'hasPlayed'>;

function setup(tracker: PositionTracker, props: Props = { stateName: 'playing', hasPlayed: true }, fileId = 91) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook((p: Props) => useProgressReporter({ item: FILM, fileId, tracker, ...p }), {
    initialProps: props,
    wrapper,
  });
}

describe('PositionTracker (attach generations, Rev 6–8)', () => {
  it('tracks timeupdate/seeked/pause only for the current generation', () => {
    const { tracker, gen } = liveTracker(100);
    at(tracker, gen, 150);
    expect(tracker.position).toBe(150);
    tracker.release(video);
    at(tracker, gen, 0); // the timeupdate queued by load() of the old generation
    expect(tracker.position).toBe(150);
    expect(tracker.gen).toBe(gen + 1);
  });

  it('release snapshots currentTime only when attached and live', () => {
    const { tracker, gen } = liveTracker(100);
    at(tracker, gen, 400);
    mediaState(video).currentTime = 402;
    tracker.release(video);
    expect(tracker.position).toBe(402);

    const g2 = tracker.beginAttach(402, { duration: 9360, resetPosition: false });
    mediaState(video).currentTime = 0; // before the start seek
    tracker.release(video); // e.g. a fatal hls.js error during loading
    expect(tracker.position).toBe(402);
    expect(g2 + 1).toBe(tracker.gen);
    tracker.release(video); // already released: nothing changes
    expect(tracker.position).toBe(402);
  });

  it('goes live only once the new source reaches its start (≥ start − 1 s on seeked/playing)', () => {
    const tracker = new PositionTracker(400, 9360);
    const gen = tracker.beginAttach(400, { duration: 9360, resetPosition: false });
    at(tracker, gen, 0); // timeupdate at 0 before the start seek
    mediaState(video).currentTime = 0;
    tracker.handle(gen, 'playing', video);
    expect(tracker.live).toBe(false);
    expect(tracker.position).toBe(400);
    at(tracker, gen, 399.2, 'seeked');
    expect(tracker.live).toBe(true);
    expect(tracker.position).toBe(399.2);
  });

  it('resetPosition starts tracking from the attach start (PLAY_FILE, play again)', () => {
    const { tracker } = liveTracker(400);
    tracker.release(video);
    tracker.beginAttach(0, { duration: 7000, resetPosition: true });
    expect(tracker.position).toBe(0);
    expect(tracker.duration).toBe(7000);
  });

  it('ended sets the position to the duration', () => {
    const { tracker, gen } = liveTracker(9000, 9360);
    at(tracker, gen, 9359.7, 'ended');
    expect(tracker.position).toBe(9360);
  });

  it('uses the element duration only when the response had none', () => {
    const tracker = new PositionTracker(0, null);
    const gen = tracker.beginAttach(0, { duration: null, resetPosition: false });
    mediaState(video).duration = 1320.5;
    at(tracker, gen, 5, 'seeked');
    expect(tracker.duration).toBe(1320.5);
    const { tracker: t2, gen: g2 } = liveTracker(0, 9360);
    mediaState(video).duration = 9999;
    at(t2, g2, 5);
    expect(t2.duration).toBe(9360);
  });

  it('notifies subscribers of accepted events only', () => {
    const tracker = new PositionTracker(400, 9360);
    const gen = tracker.beginAttach(400, { duration: 9360, resetPosition: false });
    const seen: string[] = [];
    tracker.subscribe((type) => seen.push(type));
    at(tracker, gen, 0, 'pause');
    at(tracker, gen, 400, 'seeked');
    at(tracker, gen, 401, 'pause');
    expect(seen).toEqual(['seeked', 'pause']);
  });
});

describe('useProgressReporter', () => {
  it('reports every 10 s only while playing', async () => {
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker);
    at(tracker, gen, 105);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    at(tracker, gen, 115);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await until(() => expect(puts()).toHaveLength(2));
    expect(await positions()).toEqual([105, 115]);
    expect((await bodies())[0]).toEqual({
      item_type: 'film',
      item_id: 12,
      media_file_id: 91,
      position_seconds: 105,
      duration_seconds: 9360,
    });

    view.rerender({ stateName: 'paused', hasPlayed: true });
    at(tracker, gen, 130, 'seeked'); // (paused seek → one debounced report)
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    await until(() => expect(puts()).toHaveLength(3));
    await tick();
    expect(puts()).toHaveLength(3);
  });

  it('skips periodic reports within 1 s of the last one', async () => {
    const { tracker, gen } = liveTracker(100);
    setup(tracker);
    at(tracker, gen, 105);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    at(tracker, gen, 105.6);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await tick();
    expect(await positions()).toEqual([105]);
  });

  it('reports on pause (even within 1 s), seeked (debounced 1 s) and ended (= duration)', async () => {
    const { tracker, gen } = liveTracker(100);
    setup(tracker);
    at(tracker, gen, 100.4, 'pause');
    await until(() => expect(puts()).toHaveLength(1));
    at(tracker, gen, 300, 'seeked');
    at(tracker, gen, 500, 'seeked');
    await act(() => vi.advanceTimersByTimeAsync(999));
    expect(puts()).toHaveLength(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    await until(() => expect(puts()).toHaveLength(2));
    at(tracker, gen, 9359, 'ended');
    await until(() => expect(puts()).toHaveLength(3));
    expect(await positions()).toEqual([100.4, 500, 9360]);
  });

  it.each<PlayerStateName>(['requesting', 'preparing', 'switching', 'blocked', 'loading', 'recovering'])(
    'sends nothing while %s',
    async (stateName) => {
      const { tracker, gen } = liveTracker(100);
      setup(tracker, { stateName, hasPlayed: true });
      at(tracker, gen, 150, 'pause');
      at(tracker, gen, 200, 'seeked');
      await act(() => vi.advanceTimersByTimeAsync(30_000));
      await tick();
      expect(puts()).toHaveLength(0);
    },
  );

  it('no pause report before anything played', async () => {
    const { tracker, gen } = liveTracker(100);
    setup(tracker, { stateName: 'playing', hasPlayed: false });
    at(tracker, gen, 100, 'pause');
    await tick();
    expect(puts()).toHaveLength(0);
  });

  it('sends keepalive reports on pagehide and visibilitychange→hidden (not final)', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker, { stateName: 'paused', hasPlayed: true });
    at(tracker, gen, 222);
    window.dispatchEvent(new Event('pagehide'));
    await until(() => expect(puts()).toHaveLength(1));
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    await until(() => expect(puts()).toHaveLength(2));
    const progressInits = fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/api/progress')).map(([, init]) => init);
    expect(progressInits.every((init) => init?.keepalive === true)).toBe(true);
    expect(puts()[0]?.headers.get('Authorization')).toBe('Bearer token-ana');
    view.unmount();
    await until(() => expect(puts()).toHaveLength(3)); // the final report still follows
  });

  it('a 404 stops the reporter (no final either)', async () => {
    server.use(http.put('/api/progress', () => apiError(404, 'not_found')));
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker);
    at(tracker, gen, 150);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await until(() => expect(puts()).toHaveLength(1));
    at(tracker, gen, 170);
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    view.unmount();
    await tick();
    expect(puts()).toHaveLength(1);
  });

  it('a failed report is retried on the next tick', async () => {
    let n = 0;
    server.use(http.put('/api/progress', () => (++n === 1 ? apiError(500, 'internal') : HttpResponse.json(f.progress()))));
    const { tracker, gen } = liveTracker(100);
    setup(tracker);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    at(tracker, gen, 150);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await until(() => expect(puts()).toHaveLength(1));
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await until(() => expect(puts()).toHaveLength(2));
    expect(await positions()).toEqual([150, 150]);
  });

  it('watched=true sets markedWatched', async () => {
    server.use(http.put('/api/progress', () => HttpResponse.json(f.progress({ watched: true }))));
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker);
    expect(view.result.current.markedWatched).toBe(false);
    at(tracker, gen, 9000, 'pause');
    await until(() => expect(view.result.current.markedWatched).toBe(true));
  });

  it('ratingPrompt: the latest non-null prompt of a non-final report wins; a later null never clears it', async () => {
    const dune = f.ratingPrompt();
    const answers = [dune, null, f.ratingPrompt({ title: 'Dune (bis)' })];
    server.use(
      http.put('/api/progress', () => HttpResponse.json(f.progressReport({ rating_prompt: answers.shift() ?? null }))),
    );
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker);
    expect(view.result.current.ratingPrompt).toBeNull();
    at(tracker, gen, 200, 'pause');
    await until(() => expect(view.result.current.ratingPrompt).toEqual(dune));
    at(tracker, gen, 300, 'pause');
    await until(() => expect(puts()).toHaveLength(2));
    await tick();
    await tick();
    expect(view.result.current.ratingPrompt).toEqual(dune);
    at(tracker, gen, 400, 'pause');
    await until(() => expect(view.result.current.ratingPrompt?.title).toBe('Dune (bis)'));
  });

  it('the final report hands its rating_prompt to onFinalPrompt (read at send time)', async () => {
    const prompt = f.ratingPrompt();
    server.use(http.put('/api/progress', () => HttpResponse.json(f.progressReport({ rating_prompt: prompt }))));
    const { tracker } = liveTracker(100);
    const first = vi.fn();
    const latest = vi.fn();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    const view = renderHook(
      (p: { onFinalPrompt: (prompt: RatingPrompt) => void }) =>
        useProgressReporter({ item: FILM, fileId: 91, tracker, stateName: 'playing', hasPlayed: true, onFinalPrompt: p.onFinalPrompt }),
      { initialProps: { onFinalPrompt: first }, wrapper },
    );
    view.rerender({ onFinalPrompt: latest });
    view.unmount();
    await until(() => expect(latest).toHaveBeenCalledWith(prompt));
    expect(first).not.toHaveBeenCalled();
  });

  it('unmount sends one final report from the tracked values, registered in progressSync', async () => {
    queryClient.setQueryData(queryKeys.film(12), f.filmDetail({ progress: f.progress({ position_seconds: 100 }) }));
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker);
    at(tracker, gen, 400);
    tracker.release(video);
    video.load(); // the shared element is reset: currentTime 0, duration NaN
    view.unmount();
    expect(hasPendingFinal(FILM)).toBe(true);
    await until(() => expect(hasPendingFinal(FILM)).toBe(false));
    expect(await positions()).toEqual([400]);
    expect(queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.progress?.position_seconds).toBe(400);
  });

  it('sendFinal() is the only final report of the mount', async () => {
    const { tracker, gen } = liveTracker(100);
    const view = setup(tracker, { stateName: 'ended', hasPlayed: true });
    at(tracker, gen, 9360);
    act(() => view.result.current.sendFinal());
    act(() => view.result.current.sendFinal());
    view.unmount();
    await until(() => expect(puts()).toHaveLength(1));
    await tick();
    expect(puts()).toHaveLength(1);
  });

  it('a mount that never played sends no final report', async () => {
    const { tracker } = liveTracker(100);
    const view = setup(tracker, { stateName: 'requesting', hasPlayed: false });
    view.unmount();
    await tick();
    expect(puts()).toHaveLength(0);
  });

  it('StrictMode: the simulated unmount before playback does not consume the final report', async () => {
    const tracker = new PositionTracker(100, 9360);
    const view = renderHook((p: Props) => useProgressReporter({ item: FILM, fileId: 91, tracker, ...p }), {
      initialProps: { stateName: 'requesting', hasPlayed: false } as Props,
      wrapper: ({ children }: { children: ReactNode }) => (
        <StrictMode>
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        </StrictMode>
      ),
    });
    const gen = tracker.beginAttach(100, { duration: 9360, resetPosition: false });
    mediaState(video).currentTime = 100;
    tracker.handle(gen, 'playing', video);
    view.rerender({ stateName: 'playing', hasPlayed: true });
    at(tracker, gen, 400);
    view.unmount();
    await until(() => expect(puts()).toHaveLength(1));
    expect(await positions()).toEqual([400]);
  });

  it('never sends a negative position', async () => {
    const { tracker, gen } = liveTracker(0);
    const view = setup(tracker);
    at(tracker, gen, -0.3, 'pause');
    view.unmount();
    await until(() => expect(puts()).toHaveLength(2));
    expect(await positions()).toEqual([0, 0]);
  });

  it('reports carry the current file id', async () => {
    const { tracker, gen } = liveTracker(0);
    const view = renderHook(
      ({ fileId }: { fileId: number }) => useProgressReporter({ item: FILM, fileId, tracker, stateName: 'playing', hasPlayed: true }),
      {
        initialProps: { fileId: 91 },
        wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>,
      },
    );
    view.rerender({ fileId: 92 });
    at(tracker, gen, 30, 'pause');
    await until(() => expect(puts()).toHaveLength(1));
    expect((await bodies())[0]?.media_file_id).toBe(92);
  });
});
