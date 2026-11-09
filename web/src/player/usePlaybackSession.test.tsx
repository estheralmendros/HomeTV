import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { StrictMode, useReducer, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setTokenGetter } from '@/api/client';
import type { PlaybackResponse } from '@/api/types';
import * as f from '../../tests/fixtures';
import { FakeHls, hlsLog } from '../../tests/fakeHls';
import { until, advance, tick } from '../../tests/flush';
import { mediaState } from '../../tests/media';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import type { Platform } from './capabilities';
import { getClientCaps } from './capabilities';
import { initialPlayerState, playerReducer, type PlayerEvent } from './playerMachine';
import { usePlaybackSession, type PlaybackSessionOptions } from './usePlaybackSession';
import { PositionTracker, useProgressReporter } from './useProgressReporter';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const DESKTOP: Platform = { ios: false, nativeHls: false, mse: true };
const IOS: Platform = { ios: true, nativeHls: true, mse: false };
const PLAYBACK = '/api/media-files/:id/playback';

let video: HTMLVideoElement;
let rec: ReturnType<typeof recordRequests>;

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined); // diagnostics (unsupported detail, request errors)
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  setTokenGetter(() => 'token-ana');
  video = document.createElement('video');
  document.body.appendChild(video);
  rec = recordRequests();
});
afterEach(() => {
  rec.dispose();
  video.remove();
});

type Answer = PlaybackResponse | Response | (() => Response | PlaybackResponse);

/** Answers successive POST playback requests in order (the last one repeats). */
function answerPlayback(...answers: Answer[]) {
  let n = 0;
  server.use(
    http.post(PLAYBACK, () => {
      const answer = answers[Math.min(n, answers.length - 1)];
      n += 1;
      const value = typeof answer === 'function' ? answer() : answer;
      return value instanceof Response ? value : HttpResponse.json(value);
    }),
  );
}

const posts = () => rec.find('POST', /\/playback$/);
const keepalives = () => rec.find('POST', /\/keepalive$/);
const deletes = () => rec.find('DELETE', /^\/api\/hls\//);
async function postBodies() {
  await rec.settled();
  return posts().map((c) => c.body as Record<string, unknown>);
}

// Sync act() only, never awaited: awaiting a sync act's thenable reopens React's act queue for a
// macrotask, and a fetch continuation dispatching then warns.
function fire(type: string): void {
  act(() => void video.dispatchEvent(new Event(type)));
}
function at(seconds: number, type = 'timeupdate'): void {
  mediaState(video).currentTime = seconds;
  fire(type);
}

interface HarnessProps {
  platform?: Platform;
  onAppUpdated?: () => void;
  withReporter?: boolean;
  loadHls?: PlaybackSessionOptions['loadHls'];
  strict?: boolean;
}

function setup({ platform = DESKTOP, onAppUpdated, withReporter = false, loadHls, strict = false }: HarnessProps = {}, start = 100) {
  const tracker = new PositionTracker(start, null);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => {
    const tree = <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return strict ? <StrictMode>{tree}</StrictMode> : tree;
  };
  const view = renderHook(
    () => {
      const [state, dispatch] = useReducer(playerReducer, undefined, initialPlayerState);
      // Declaration order matters: the reporter's final report runs before the session releases.
      const reporter = withReporter
        ? useProgressReporter({
            item: { type: 'film', id: 12 },
            fileId: state.fileId,
            stateName: state.name,
            hasPlayed: state.hasPlayed,
            tracker,
          })
        : null;
      const options: PlaybackSessionOptions = { state, dispatch, video, tracker, platform, onAppUpdated, loadHls };
      const session = usePlaybackSession(options);
      return { state, dispatch, session, reporter };
    },
    { wrapper },
  );
  const dispatch = (e: PlayerEvent): void => {
    act(() => view.result.current.dispatch(e));
  };
  const state = () => view.result.current.state;
  const session = () => view.result.current.session;
  return { view, tracker, dispatch, state, session };
}

type Harness = ReturnType<typeof setup>;

/** LOAD → decision → attached → (hls) manifest → `playing` at the start position. */
async function startPlaying(h: Harness, start = 100) {
  h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: start });
  await until(() => expect(h.state().name).toBe('loading'));
  if (h.state().mode === 'hls' && !video.hasAttribute('src')) {
    await until(() => expect(FakeHls.last?.media).toBe(video));
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
  } else {
    fire('loadedmetadata');
  }
  at(start, 'playing');
  expect(h.state().name).toBe('playing');
}

function pauseAt(h: Harness, seconds: number): void {
  mediaState(video).currentTime = seconds;
  act(() => h.session().pause());
  fire('pause');
  expect(h.state().name).toBe('paused');
}

describe('StrictMode (dev double effects)', () => {
  it('the simulated unmount does not abort the mount: attach, media errors and exit still work', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ strict: true });
    await startPlaying(h);
    expect(FakeHls.last?.url).toBe('/api/hls/sess-1/index.m3u8');
    at(400);
    act(() => FakeHls.last?.fatal());
    await until(() => expect(posts()).toHaveLength(2));
    h.view.unmount();
    await until(() => expect(deletes().length).toBeGreaterThanOrEqual(1));
  });
});

describe('requests', () => {
  it('POST playback with the client caps, then attaches the direct source with its token', async () => {
    const h = setup();
    await startPlaying(h);
    expect(await postBodies()).toEqual([
      { client_caps: getClientCaps(), audio_stream_index: null, start_position_seconds: 100, previous_session_id: null },
    ]);
    expect(posts()[0]?.url.pathname).toBe('/api/media-files/91/playback');
    expect(video.getAttribute('src')).toBe('/api/media-files/91/stream?token=token-ana');
    expect(video.currentTime).toBe(100);
  });

  it('hls: loadSource with the untokenized URL and startPosition', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h, 250);
    expect(FakeHls.last?.url).toBe('/api/hls/sess-1/index.m3u8');
    expect(FakeHls.last?.config.startPosition).toBe(250);
  });

  it('iOS: native HLS via video.src, no hls.js', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ platform: IOS });
    await startPlaying(h, 250);
    expect(video.getAttribute('src')).toBe('/api/hls/sess-1/index.m3u8');
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('503 preparing ×2 then 200: identical bodies 5 s apart, latest estimate, then loading', async () => {
    answerPlayback(
      () => apiError(503, 'preparing', { headers: { 'Retry-After': '5' }, details: { estimated_seconds: 150 } }),
      () => apiError(503, 'preparing', { headers: { 'Retry-After': '5' }, details: { estimated_seconds: 40 } }),
      f.playbackDirect(),
    );
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 100 });
    await until(() => expect(h.state().name).toBe('preparing'));
    expect(h.state().prepareEstimateS).toBe(150);
    await advance(4_999);
    expect(posts()).toHaveLength(1);
    await advance(1);
    await until(() => expect(h.state().prepareEstimateS).toBe(40));
    expect(h.state().name).toBe('preparing');
    await advance(5_000);
    await until(() => expect(h.state().name).toBe('loading'));
    const bodies = await postBodies();
    expect(bodies).toHaveLength(3);
    expect(new Set(bodies.map((b) => JSON.stringify(b))).size).toBe(1);
  });

  it('falls back to details.retry_after_seconds, then 5 s', async () => {
    answerPlayback(() => apiError(503, 'preparing', { details: { retry_after_seconds: 8 } }), f.playbackDirect());
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('preparing'));
    await advance(7_999);
    expect(posts()).toHaveLength(1);
    await advance(1);
    await until(() => expect(h.state().name).toBe('loading'));
  });

  it('the preparing cap: first estimate 90 s → timeout once 180 s have elapsed, not before', async () => {
    answerPlayback(() => apiError(503, 'preparing', { headers: { 'Retry-After': '5' }, details: { estimated_seconds: 90 } }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('preparing'));
    for (let elapsed = 0; elapsed < 175_000; elapsed += 5_000) {
      await advance(5_000);
      await until(() => expect(h.state().name).toBe('preparing'));
    }
    expect(h.state().name).toBe('preparing');
    await advance(5_000);
    await until(() => expect(h.state().error?.kind).toBe('prepare_timeout'));
    const count = posts().length;
    await advance(60_000);
    expect(posts()).toHaveLength(count);
  });

  it('Cancel during preparing stops the loop', async () => {
    answerPlayback(() => apiError(503, 'preparing', { headers: { 'Retry-After': '5' } }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('preparing'));
    act(() => h.session().cancel());
    expect(h.state().name).toBe('exited');
    await advance(60_000);
    expect(posts()).toHaveLength(1);
  });

  it('unmount during preparing clears the timer', async () => {
    answerPlayback(() => apiError(503, 'preparing', { headers: { 'Retry-After': '5' } }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('preparing'));
    h.view.unmount();
    await advance(60_000);
    expect(posts()).toHaveLength(1);
  });

  it('422 invalid_audio_stream retries once with audio_stream_index null', async () => {
    answerPlayback(() => apiError(422, 'invalid_audio_stream'), f.playbackDirect());
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0, audioStreamIndex: 3 });
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies()).map((b) => b.audio_stream_index)).toEqual([3, null]);
  });

  it('a remembered audio index answered unsupported re-sends exactly once with null (no unsupported screen)', async () => {
    vi.mocked(console.warn).mockClear();
    answerPlayback(f.playbackUnsupported({ reason: 'audio_codec_unsupported' }), f.playbackDirect({ audio_stream_index: 1 }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0, audioStreamIndex: 3, audioFromChoice: true });
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies()).map((b) => b.audio_stream_index)).toEqual([3, null]);
    expect(h.state().unsupported).toBeNull();
  });

  it('a remembered audio index answered invalid_audio_stream: one re-send with null through the machine', async () => {
    answerPlayback(() => apiError(422, 'invalid_audio_stream'), f.playbackDirect());
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0, audioStreamIndex: 3, audioFromChoice: true });
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies()).map((b) => b.audio_stream_index)).toEqual([3, null]);
    expect(h.state().lastIntent).toMatchObject({ audioStreamIndex: null, audioFromChoice: false });
  });

  it('the re-send unsupported too: the unsupported screen as today (two POSTs, never the rejected index again)', async () => {
    answerPlayback(f.playbackUnsupported());
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0, audioStreamIndex: 3, audioFromChoice: true });
    await until(() => expect(h.state().name).toBe('unsupported'));
    expect((await postBodies()).map((b) => b.audio_stream_index)).toEqual([3, null]);
  });

  it('playFile passes the matched audio index of that file', async () => {
    answerPlayback(f.playbackUnsupported(), f.playbackDirect({ url: '/api/media-files/92/stream' }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('unsupported'));
    act(() => h.session().playFile(92, 0, 4));
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies()).map((b) => b.audio_stream_index)).toEqual([null, 4]);
  });

  it('busy and keyframe_index_failed errors reach the machine; the raw detail is only logged', async () => {
    const warn = vi.mocked(console.warn);
    answerPlayback(() => apiError(500, 'transcoder_failed', { details: { detail: 'keyframe_index_failed: stalled' } }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().error?.kind).toBe('version_failed'));
    expect(h.state().failedFileIds).toEqual([91]);
    expect(warn.mock.calls.flat().join(' ')).toContain('keyframe_index_failed: stalled');
  });

  it('a PLAY_FILE re-requests the alternative exactly once', async () => {
    answerPlayback(f.playbackUnsupported(), f.playbackDirect({ url: '/api/media-files/92/stream' }));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(h.state().name).toBe('unsupported'));
    act(() => h.session().playFile(92, 0));
    await until(() => expect(h.state().name).toBe('loading'));
    expect(posts().map((c) => c.url.pathname)).toEqual(['/api/media-files/91/playback', '/api/media-files/92/playback']);
  });
});

describe('autoplay rejection', () => {
  it('NotAllowedError → blocked; a tap plays synchronously and PLAYING → playing', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    const h = setup();
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 100 });
    await until(() => expect(h.state().name).toBe('loading'));
    fire('loadedmetadata');
    await until(() => expect(h.state().name).toBe('blocked'));
    act(() => h.session().play());
    expect(play).toHaveBeenCalledTimes(2);
    at(100, 'playing');
    expect(h.state().name).toBe('playing');
  });
});

describe('keep-alive', () => {
  it('every 30 s while paused in HLS mode, none while playing with hls.js', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    await advance(90_000);
    expect(keepalives()).toHaveLength(0);
    pauseAt(h, 130);
    await advance(29_999);
    expect(keepalives()).toHaveLength(0);
    await advance(1);
    expect(keepalives()).toHaveLength(1);
    expect(keepalives()[0]?.url.pathname).toBe('/api/hls/sess-1/keepalive');
    await advance(60_000);
    expect(keepalives()).toHaveLength(3);
  });

  it('also while blocked and buffering', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    fire('waiting');
    expect(h.state().name).toBe('buffering');
    await advance(30_000);
    expect(keepalives()).toHaveLength(1);
  });

  it('also while playing on native HLS (iOS)', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ platform: IOS });
    await startPlaying(h);
    await advance(60_000);
    expect(keepalives()).toHaveLength(2);
  });

  it('never in direct mode', async () => {
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 130);
    await advance(31 * 60_000);
    expect(keepalives()).toHaveLength(0);
    expect(deletes()).toHaveLength(0);
    expect(h.state().sessionReleased).toBe(false);
  });

  it('a 404 marks the session lost: no more keep-alives', async () => {
    answerPlayback(f.playbackHls());
    server.use(http.post('/api/hls/:sid/keepalive', () => apiError(404, 'session_not_found')));
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 130);
    await advance(30_000);
    expect(keepalives()).toHaveLength(1);
    await advance(120_000);
    expect(keepalives()).toHaveLength(1);
  });
});

describe('30 min pause release', () => {
  it('keep-alives for 29 min, then exactly one DELETE; Play re-requests at the paused position', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2', url: '/api/hls/sess-2/index.m3u8' }));
    const h = setup();
    await startPlaying(h);
    at(4325);
    pauseAt(h, 4325);
    await advance(29 * 60_000);
    expect(keepalives()).toHaveLength(58);
    expect(deletes()).toHaveLength(0);
    await advance(60_000);
    expect(deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-1']);
    expect(h.state()).toMatchObject({ name: 'paused', sessionReleased: true });
    expect(FakeHls.instances[0]?.destroyed).toBe(true);
    const kept = keepalives().length;
    await advance(10 * 60_000);
    expect(keepalives()).toHaveLength(kept);
    expect(deletes()).toHaveLength(1);

    act(() => h.session().play());
    expect(h.state().name).toBe('requesting');
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies())[1]).toMatchObject({ start_position_seconds: 4325, previous_session_id: null, audio_stream_index: 1 });
    await until(() => expect(FakeHls.last?.id).toBe(2));
    expect(FakeHls.last?.config.startPosition).toBe(4325);
  });

  it('a play/pause cycle at 20 min restarts the 30 min timer', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 300);
    await advance(20 * 60_000);
    act(() => h.session().play());
    at(300, 'playing');
    pauseAt(h, 301);
    await advance(29 * 60_000);
    expect(deletes()).toHaveLength(0);
    await advance(60_000);
    expect(deletes()).toHaveLength(1);
  });

  it('works the same on native HLS (iOS)', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ platform: IOS });
    await startPlaying(h);
    pauseAt(h, 300);
    await advance(30 * 60_000);
    expect(deletes()).toHaveLength(1);
    expect(video.hasAttribute('src')).toBe(false);
  });

  it('unmount after a release sends no second DELETE', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 300);
    await advance(30 * 60_000);
    h.view.unmount();
    await advance(0);
    expect(deletes()).toHaveLength(1);
  });

  it('play again from ended after a release requests at 0', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    at(9360, 'ended');
    expect(h.state().name).toBe('ended');
    await advance(30 * 60_000);
    expect(h.state().sessionReleased).toBe(true);
    act(() => h.session().play());
    await until(() => expect(posts()).toHaveLength(2));
    expect((await postBodies())[1]).toMatchObject({ start_position_seconds: 0 });
    expect(h.tracker.position).toBe(9360); // until the new attach resets it
    await until(() => expect(h.state().name).toBe('loading'));
    expect(h.tracker.position).toBe(0);
  });
});

describe('audio switch', () => {
  it('HLS: sends previous_session_id at the saved position; the old session is never kept alive or DELETEd', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2', url: '/api/hls/sess-2/index.m3u8', audio_stream_index: 2 }));
    const h = setup();
    await startPlaying(h);
    at(400);
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    act(() => h.session().switchAudio(2));
    expect(h.state().name).toBe('switching');
    expect(pause).toHaveBeenCalled();
    await until(() => expect(h.state().name).toBe('loading'));
    expect((await postBodies())[1]).toEqual({
      client_caps: getClientCaps(),
      audio_stream_index: 2,
      start_position_seconds: 400,
      previous_session_id: 'sess-1',
    });
    await until(() => expect(FakeHls.last?.id).toBe(2));
    expect(hlsLog).toEqual(['new#1', 'loadSource#1', 'attachMedia#1', 'destroy#1', 'new#2', 'loadSource#2', 'attachMedia#2']);
    expect(FakeHls.last?.config.startPosition).toBe(400);
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
    at(400, 'playing');
    pauseAt(h, 410);
    await advance(60_000);
    h.view.unmount();
    await advance(0);
    expect(keepalives().every((c) => c.url.pathname === '/api/hls/sess-2/keepalive')).toBe(true);
    expect(deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-2']);
  });

  it('HLS answered unsupported: a second POST with the previous audio at t, previous_session_id null', async () => {
    answerPlayback(f.playbackHls(), f.playbackUnsupported({ reason: 'keyframes_too_sparse' }), f.playbackHls({ session_id: 'sess-3' }));
    const h = setup();
    await startPlaying(h);
    at(400);
    act(() => h.session().switchAudio(2));
    await until(() => expect(posts()).toHaveLength(3));
    expect(h.state().notice?.kind).toBe('audio_unsupported');
    expect((await postBodies())[2]).toMatchObject({ audio_stream_index: 1, start_position_seconds: 400, previous_session_id: null });
    h.view.unmount();
    await advance(0);
    expect(deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-3']);
  });

  it('direct answered unsupported: toast, no second POST, the old source resumes in place', async () => {
    answerPlayback(f.playbackDirect(), f.playbackUnsupported());
    const h = setup();
    await startPlaying(h);
    at(400);
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    act(() => h.session().switchAudio(2));
    await until(() => expect(h.state().name).toBe('playing'));
    expect(h.state().notice?.kind).toBe('audio_unsupported');
    expect(posts()).toHaveLength(2);
    expect(play).toHaveBeenCalled();
    expect(video.getAttribute('src')).toBe('/api/media-files/91/stream?token=token-ana');
  });

  it('direct answered hls_disk_full: toast, stays on the old source', async () => {
    answerPlayback(f.playbackDirect(), () => apiError(503, 'hls_disk_full'));
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 400);
    act(() => h.session().switchAudio(2));
    await until(() => expect(h.state().name).toBe('paused'));
    expect(h.state().notice?.kind).toBe('switch_failed');
    expect(video.hasAttribute('src')).toBe(true);
  });

  it('HLS answered hls_disk_full: busy screen; Retry re-requests at t with the chosen audio', async () => {
    answerPlayback(f.playbackHls(), () => apiError(503, 'hls_disk_full'), f.playbackHls({ session_id: 'sess-4' }));
    const h = setup();
    await startPlaying(h);
    at(400);
    act(() => h.session().switchAudio(2));
    await until(() => expect(h.state().error?.kind).toBe('busy'));
    act(() => h.session().retry());
    await until(() => expect(posts()).toHaveLength(3));
    expect((await postBodies())[2]).toMatchObject({ audio_stream_index: 2, start_position_seconds: 400, previous_session_id: null });
  });

  it('preparing during an HLS switch + Cancel: new POST with the previous audio at t, no keep-alive meanwhile', async () => {
    answerPlayback(
      f.playbackHls(),
      () => apiError(503, 'preparing', { headers: { 'Retry-After': '5' } }),
      f.playbackHls({ session_id: 'sess-5' }),
    );
    const h = setup();
    await startPlaying(h);
    pauseAt(h, 400);
    act(() => h.session().switchAudio(2));
    await until(() => expect(h.state().name).toBe('preparing'));
    await advance(4_000);
    act(() => h.session().cancel());
    await until(() => expect(posts()).toHaveLength(3));
    expect((await postBodies())[2]).toMatchObject({ audio_stream_index: 1, start_position_seconds: 400, previous_session_id: null });
    expect(keepalives()).toHaveLength(0);
  });
});

describe('recovery', () => {
  it('a fatal hls.js error re-requests at the saved position with previous_session_id null', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2' }));
    const h = setup();
    await startPlaying(h);
    at(400);
    act(() => FakeHls.last?.fatal());
    expect(h.state().name).toBe('recovering');
    await until(() => expect(posts()).toHaveLength(2));
    expect((await postBodies())[1]).toMatchObject({ start_position_seconds: 400, previous_session_id: null, audio_stream_index: 1 });
  });

  it('a fatal error before tracking went live re-requests at the original start', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2' }));
    const h = setup({}, 400);
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 400 });
    await until(() => expect(FakeHls.last?.media).toBe(video));
    act(() => FakeHls.last?.fatal());
    await until(() => expect(posts()).toHaveLength(2));
    expect((await postBodies())[1]).toMatchObject({ start_position_seconds: 400 });
  });

  it('two fatal errors 40 s apart both recover; 10 s apart → error', async () => {
    answerPlayback(f.playbackHls());
    const h = setup();
    await startPlaying(h);
    act(() => FakeHls.last?.fatal());
    await until(() => expect(FakeHls.last?.id).toBe(2));
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
    at(100, 'playing');
    await advance(40_000);
    act(() => FakeHls.last?.fatal());
    expect(h.state().name).toBe('recovering');
    await until(() => expect(FakeHls.last?.id).toBe(3));
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
    at(100, 'playing');
    await advance(10_000);
    act(() => FakeHls.last?.fatal());
    expect(h.state().error?.kind).toBe('failed');
    expect(posts()).toHaveLength(3);
  });

  it('direct MEDIA_ERR_NETWORK with GET media-file 404 → gone, no re-request', async () => {
    server.use(http.get('/api/media-files/:id', () => apiError(404, 'file_missing')));
    const h = setup();
    await startPlaying(h);
    at(300);
    mediaState(video).error = { code: 2 };
    fire('error');
    await until(() => expect(h.state().error?.kind).toBe('gone'));
    expect(rec.find('GET', '/api/media-files/91')).toHaveLength(1);
    expect(posts()).toHaveLength(1);
  });

  it('direct MEDIA_ERR_NETWORK with GET media-file 200 → one re-request at the current time', async () => {
    const h = setup();
    await startPlaying(h);
    at(300);
    mediaState(video).error = { code: 2 };
    fire('error');
    await until(() => expect(posts()).toHaveLength(2));
    expect((await postBodies())[1]).toMatchObject({ start_position_seconds: 300 });
  });

  it('the old source is ignored while switching (its session was stopped)', async () => {
    answerPlayback(f.playbackHls(), () => new Promise<Response>(() => undefined) as unknown as Response);
    const h = setup();
    await startPlaying(h);
    act(() => h.session().switchAudio(2));
    act(() => FakeHls.instances[0]?.fatal());
    expect(h.state().name).toBe('switching');
  });

  it('APP_UPDATED (hls.js chunk) is reported to the page', async () => {
    const reload = await import('@/lib/reload');
    vi.spyOn(reload.reloader, 'reload').mockImplementation(() => undefined);
    window.sessionStorage.setItem('hometv.chunkReloadAt', String(Date.now()));
    const onAppUpdated = vi.fn();
    answerPlayback(f.playbackHls());
    const failing = vi.fn(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module: x')));
    const h = setup({ onAppUpdated, loadHls: failing });
    h.dispatch({ type: 'LOAD', fileId: 91, startPositionSeconds: 0 });
    await until(() => expect(onAppUpdated).toHaveBeenCalled());
  });
});

describe('exit', () => {
  it('unmount in HLS mode DELETEs the session with keepalive', async () => {
    answerPlayback(f.playbackHls());
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const h = setup();
    await startPlaying(h);
    h.view.unmount();
    await advance(0);
    expect(deletes().map((c) => c.url.pathname)).toEqual(['/api/hls/sess-1']);
    const init = fetchSpy.mock.calls.find(([url, i]) => String(url).endsWith('/api/hls/sess-1') && i?.method === 'DELETE')?.[1];
    expect(init?.keepalive).toBe(true);
    expect(FakeHls.instances[0]?.destroyed).toBe(true);
  });

  it('direct mode sends no DELETE and resets the element', async () => {
    const h = setup();
    await startPlaying(h);
    h.view.unmount();
    await advance(0);
    expect(deletes()).toHaveLength(0);
    expect(video.hasAttribute('src')).toBe(false);
  });

  it('the final report (tracked 400) is sent before the release and the DELETE', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ withReporter: true });
    await startPlaying(h);
    at(400);
    h.view.unmount();
    await advance(0);
    await rec.settled();
    const order = rec.calls.filter((c) => c.method !== 'POST').map((c) => `${c.method} ${c.url.pathname}`);
    expect(order).toEqual(['PUT /api/progress', 'DELETE /api/hls/sess-1']);
    expect(rec.find('PUT', '/api/progress')[0]?.body).toMatchObject({ position_seconds: 400 });
    await tick();
  });

  it('a pause release then Back reports the paused position, not 0', async () => {
    answerPlayback(f.playbackHls());
    const h = setup({ withReporter: true });
    await startPlaying(h);
    at(4325);
    pauseAt(h, 4325);
    await advance(30 * 60_000);
    expect(video.currentTime).toBe(0);
    h.view.unmount();
    await advance(0);
    await rec.settled();
    const finals = rec.find('PUT', '/api/progress').map((c) => (c.body as { position_seconds: number }).position_seconds);
    expect(finals).toEqual([4325, 4325]); // the pause report, then the final
  });

  it('an audio switch at 400 then Back during loading reports 400', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2' }));
    const h = setup({ withReporter: true });
    await startPlaying(h);
    at(400);
    act(() => h.session().switchAudio(2));
    await until(() => expect(h.state().name).toBe('loading'));
    at(0); // the new source's early timeupdate at 0
    h.view.unmount();
    await advance(0);
    await rec.settled();
    const positions = rec.find('PUT', '/api/progress').map((c) => (c.body as { position_seconds: number }).position_seconds);
    expect(positions).toEqual([400]);
  });

  it('a recovery where the new source emits timeupdate at 0 before its seek never reports 0', async () => {
    answerPlayback(f.playbackHls(), f.playbackHls({ session_id: 'sess-2' }));
    const h = setup({ withReporter: true });
    await startPlaying(h);
    at(400);
    act(() => FakeHls.last?.fatal());
    await until(() => expect(FakeHls.last?.id).toBe(2));
    at(0);
    act(() => FakeHls.last?.emit(FakeHls.Events.MANIFEST_PARSED));
    at(400, 'playing');
    at(405);
    await advance(10_000);
    await rec.settled();
    const positions = rec.find('PUT', '/api/progress').map((c) => (c.body as { position_seconds: number }).position_seconds);
    expect(positions.length).toBeGreaterThan(0);
    expect(positions.every((p) => p >= 400)).toBe(true);
  });
});
