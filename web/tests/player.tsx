// PlayerPage test harness. Renders the real PlayerRoute
// shell at /watch/:type/:id inside the provider stack, with '/' in history (Back → '/').
//
//   vi.mock('hls.js', () => import('../../tests/fakeHls'));   // in the test file
//   useFakePlayerTimers();                                    // optional, before render
//   const p = renderPlayer('/watch/film/12');
//   await p.startDirect(100);                                 // decision → src → playing at 100
//
// Waiting: use `until` / `advance` from tests/flush (real macrotasks; fake time only moves with
// `advance`). Element events: `p.at(400)` (timeupdate at 400), `p.fire('pause')`.
// Never await a synchronous act().
import { notifyManager } from '@tanstack/react-query';
import { act, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { RouteObject } from 'react-router-dom';
import { expect, vi } from 'vitest';
import type { EpisodeDetail, PlaybackResponse } from '@/api/types';
import { PlayerRoute } from '@/player/PlayerRoute';
import { FakeHls } from './fakeHls';
import * as f from './fixtures';
import { until } from './flush';
import { mediaState } from './media';
import { recordRequests, server } from './msw/server';
import { renderWithProviders, type ProviderOptions } from './renderWithProviders';

export const PLAYER_PATH = '/watch/:type/:id';
export const PLAYBACK = '/api/media-files/:id/playback';

const realSetImmediate = globalThis.setImmediate;

/**
 * setTimeout/setInterval/Date faked; setImmediate/nextTick stay real (MSW, tests/flush). React
 * Query notifies observers through `setTimeout(0)` by default, so its scheduler moves to a real
 * macrotask (tests/setup.ts restores the default after each test).
 */
export function useFakePlayerTimers(): void {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  notifyManager.setScheduler((callback) => realSetImmediate(callback));
}

type Answer = PlaybackResponse | Response | ((fileId: number) => Response | PlaybackResponse);

/** Answers successive POST playback requests in order (the last one repeats). */
export function answerPlayback(...answers: Answer[]): void {
  let n = 0;
  server.use(
    http.post(PLAYBACK, ({ params }) => {
      const answer = answers[Math.min(n, answers.length - 1)] as Answer;
      n += 1;
      const value = typeof answer === 'function' ? answer(Number(params.id)) : answer;
      return value instanceof Response ? value : HttpResponse.json(value);
    }),
  );
}

/** Serves GET /api/episodes/{id} from a map (404 for unknown ids); a null `track_choice` answers the saved one. */
export function serveEpisodes(episodes: EpisodeDetail[], delayFor: Record<number, Promise<void>> = {}): void {
  server.use(
    http.get('/api/episodes/:id', async ({ params }) => {
      const id = Number(params.id);
      await delayFor[id];
      const episode = episodes.find((e) => e.id === id);
      return episode
        ? HttpResponse.json({ ...episode, track_choice: episode.track_choice ?? f.savedTrackChoice('show', episode.show_id) })
        : HttpResponse.json({ error: { code: 'not_found', message: 'not found', details: {} } }, { status: 404 });
    }),
  );
}

/** A promise with its resolve function (to hold an MSW response). */
export function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve = () => undefined as void;
  const promise = new Promise<void>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/**
 * jsdom has no Fullscreen API: `requestFullscreen` sets
 * `document.fullscreenElement` and dispatches `fullscreenchange`; so does `exitFullscreen`.
 */
export function stubFullscreen(): () => void {
  let element: Element | null = null;
  const docProps = {
    fullscreenEnabled: { configurable: true, get: () => true },
    fullscreenElement: { configurable: true, get: () => element },
  };
  Object.defineProperties(document, docProps);
  const request = vi.fn(function (this: Element) {
    element = this;
    document.dispatchEvent(new Event('fullscreenchange'));
    return Promise.resolve();
  });
  const exit = vi.fn(() => {
    element = null;
    document.dispatchEvent(new Event('fullscreenchange'));
    return Promise.resolve();
  });
  Element.prototype.requestFullscreen = request;
  document.exitFullscreen = exit;
  return () => {
    delete (document as { fullscreenEnabled?: unknown }).fullscreenEnabled;
    delete (document as { fullscreenElement?: unknown }).fullscreenElement;
    delete (Element.prototype as { requestFullscreen?: unknown }).requestFullscreen;
    delete (document as { exitFullscreen?: unknown }).exitFullscreen;
  };
}

export interface PlayerRenderOptions extends ProviderOptions {
  routes?: RouteObject[];
}

export function renderPlayer(url: string, options: PlayerRenderOptions = {}) {
  const rec = recordRequests();
  const view = renderWithProviders(<PlayerRoute />, { path: PLAYER_PATH, route: url, history: ['/'], ...options });
  const video = () => {
    const el = screen.getByTestId('player-root').querySelector('video');
    if (!el) throw new Error('no <video> in the player shell');
    return el;
  };
  const fire = (type: string, el: HTMLVideoElement = video()) => {
    act(() => void el.dispatchEvent(new Event(type)));
  };
  const at = (seconds: number, type = 'timeupdate') => {
    const el = video();
    mediaState(el).currentTime = seconds;
    fire(type, el);
  };

  const posts = () => rec.find('POST', /\/playback$/);
  const postBodies = async () => {
    await rec.settled();
    return posts().map((c) => c.body as Record<string, unknown>);
  };
  const puts = () => rec.find('PUT', '/api/progress');
  const putBodies = async () => {
    await rec.settled();
    return puts().map((c) => c.body as { position_seconds: number; media_file_id: number; duration_seconds: number | null; item_id: number });
  };
  const deletes = () => rec.find('DELETE', /^\/api\/hls\//);
  const keepalives = () => rec.find('POST', /\/keepalive$/);

  /** The decision is attached as a direct source; loadedmetadata seeks + plays; `playing` at `start`. */
  const startDirect = async (start: number, src?: string | RegExp) => {
    await until(() => expect(video().getAttribute('src') ?? '').toMatch(src ?? /\/stream\?token=/));
    fire('loadedmetadata');
    at(start, 'playing');
  };
  /** hls.js attached; MANIFEST_PARSED plays; `playing` at `start`. */
  const startHls = async (start: number, instance = FakeHls.instances.length + 1) => {
    await until(() => expect(FakeHls.instances[instance - 1]?.media).toBe(video()));
    act(() => FakeHls.instances[instance - 1]?.emit(FakeHls.Events.MANIFEST_PARSED));
    at(start, 'playing');
  };
  /** User pause at `seconds` (the element fires `pause`). */
  const pauseAt = (seconds: number) => {
    const el = video();
    mediaState(el).currentTime = seconds;
    el.pause();
    fire('pause', el);
  };

  return { ...view, rec, video, fire, at, posts, postBodies, puts, putBodies, deletes, keepalives, startDirect, startHls, pauseAt };
}

export type PlayerHarness = ReturnType<typeof renderPlayer>;
