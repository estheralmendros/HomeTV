import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setTokenGetter } from '@/api/client';
import { reloader } from '@/lib/reload';
import * as f from '../../tests/fixtures';
import { FakeHls, hlsLog } from '../../tests/fakeHls';
import { tick } from '../../tests/flush';
import { mediaState } from '../../tests/media';
import { attachSource, HLS_CONFIG, playMedia, type AttachOptions, type MediaSignal } from './attachSource';
import type { Platform } from './capabilities';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const IOS: Platform = { ios: true, nativeHls: true, mse: false };
const DESKTOP: Platform = { ios: false, nativeHls: false, mse: true };
const MSE_ENV = { MediaSource: class {} };
const MMS_ONLY_ENV = { ManagedMediaSource: class {} };

const hls = f.playbackHls({ session_id: 'sess-1' });
const direct = f.playbackDirect({ url: '/api/media-files/91/stream' });

let video: HTMLVideoElement;
let signals: MediaSignal[];

beforeEach(() => {
  setTokenGetter(() => 'tok en');
  video = document.createElement('video');
  document.body.appendChild(video);
  signals = [];
});
afterEach(() => video.remove());

function attach(overrides: Partial<AttachOptions> = {}) {
  return attachSource({
    video,
    response: hls,
    startPosition: 400,
    platform: DESKTOP,
    mediaSourceEnv: MSE_ENV,
    onSignal: (s) => signals.push(s),
    ...overrides,
  });
}

const fire = (type: string) => video.dispatchEvent(new Event(type));
const types = () => signals.map((s) => s.type);

describe('attachSource: native HLS (iOS/iPadOS)', () => {
  it('sets the untokenized m3u8, never loads hls.js, and seeks on loadedmetadata', async () => {
    const loadHls = vi.fn();
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const handle = attach({ platform: IOS, loadHls });
    expect(handle.kind).toBe('native');
    expect(video.getAttribute('src')).toBe('/api/hls/sess-1/index.m3u8');
    expect(loadHls).not.toHaveBeenCalled();
    expect(FakeHls.instances).toHaveLength(0);
    expect(play).not.toHaveBeenCalled();
    fire('loadedmetadata');
    expect(video.currentTime).toBe(400);
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('re-seeks once on canplay when the position is more than 1 s off', () => {
    attach({ platform: IOS });
    fire('loadedmetadata');
    video.currentTime = 380;
    fire('canplay');
    expect(video.currentTime).toBe(400);
    video.currentTime = 370;
    fire('canplay');
    expect(video.currentTime).toBe(370);
  });

  it('leaves disableRemotePlayback unset (AirPlay) even with only ManagedMediaSource', () => {
    attach({ platform: IOS, mediaSourceEnv: MMS_ONLY_ENV });
    expect(video.disableRemotePlayback).not.toBe(true);
  });

  it('detach resets src with removeAttribute + load() synchronously', () => {
    const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
    const handle = attach({ platform: IOS });
    const removeAttribute = vi.spyOn(video, 'removeAttribute');
    handle.detach();
    expect(removeAttribute).toHaveBeenCalledWith('src');
    expect(load).toHaveBeenCalledTimes(1);
    expect(video.hasAttribute('src')).toBe(false);
    expect(handle.detached).toBe(true);
  });

  it.each([
    [2, 'network'],
    [3, 'network'],
    [4, 'decode'],
  ])('video.error code %s → FATAL %s', (code, kind) => {
    attach({ platform: IOS });
    mediaState(video).error = { code };
    fire('error');
    expect(signals).toEqual([{ type: 'FATAL', source: 'native', kind }]);
  });

  it('waiting > 45 s without a progress event → FATAL network', () => {
    vi.useFakeTimers();
    attach({ platform: IOS });
    fire('waiting');
    vi.advanceTimersByTime(30_000);
    fire('progress'); // restarts the stall timer
    vi.advanceTimersByTime(45_000 - 1);
    expect(types()).toEqual(['WAITING']);
    vi.advanceTimersByTime(1);
    expect(signals.at(-1)).toEqual({ type: 'FATAL', source: 'native', kind: 'network' });
  });

  it('playing clears the stall timer', () => {
    vi.useFakeTimers();
    attach({ platform: IOS });
    fire('waiting');
    vi.advanceTimersByTime(20_000);
    fire('playing');
    vi.advanceTimersByTime(60_000);
    expect(types()).toEqual(['WAITING', 'PLAYING']);
  });
});

describe('attachSource: hls.js (desktop, Safari included)', () => {
  it('constructs Hls with HLS_CONFIG + startPosition, loads the untokenized URL and plays on MANIFEST_PARSED', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const handle = attach();
    expect(handle.kind).toBe('hls');
    await handle.ready;
    const instance = FakeHls.last;
    expect(instance?.config).toEqual({ ...HLS_CONFIG, startPosition: 400 });
    expect(instance?.url).toBe('/api/hls/sess-1/index.m3u8');
    expect(instance?.media).toBe(video);
    expect(hlsLog).toEqual(['new#1', 'loadSource#1', 'attachMedia#1']);
    expect(play).not.toHaveBeenCalled();
    instance?.emit(FakeHls.Events.MANIFEST_PARSED);
    expect(play).toHaveBeenCalledTimes(1);
    expect(video.disableRemotePlayback).not.toBe(true);
  });

  it('HLS_CONFIG tolerates 30 s segments and the 20 s init.mp4 wait', () => {
    const frag = HLS_CONFIG.fragLoadPolicy.default;
    expect(frag.maxLoadTimeMs).toBeGreaterThanOrEqual(60_000);
    expect(frag.maxTimeToFirstByteMs).toBeGreaterThan(20_000);
    expect(HLS_CONFIG.maxBufferLength).toBeGreaterThanOrEqual(90);
    expect(HLS_CONFIG).toMatchObject({
      enableWorker: true,
      lowLatencyMode: false,
      preferManagedMediaSource: false,
      maxMaxBufferLength: 180,
      maxBufferSize: 200 * 1000 * 1000,
      backBufferLength: 30,
      enableCEA708Captions: false,
      enableWebVTT: false,
      fragLoadPolicy: {
        default: {
          maxTimeToFirstByteMs: 30_000,
          maxLoadTimeMs: 90_000,
          timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 },
          errorRetry: { maxNumRetry: 4, retryDelayMs: 1000, maxRetryDelayMs: 8000 },
        },
      },
      playlistLoadPolicy: {
        default: {
          maxTimeToFirstByteMs: 10_000,
          maxLoadTimeMs: 20_000,
          timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 },
          errorRetry: { maxNumRetry: 2, retryDelayMs: 1000, maxRetryDelayMs: 8000 },
        },
      },
    });
  });

  it('sets disableRemotePlayback before attaching when only ManagedMediaSource exists', async () => {
    let atAttach: boolean | undefined;
    const original = FakeHls.prototype.attachMedia;
    vi.spyOn(FakeHls.prototype, 'attachMedia').mockImplementation(function (this: FakeHls, media) {
      atAttach = (media as HTMLVideoElement).disableRemotePlayback;
      original.call(this, media);
    });
    await attach({ mediaSourceEnv: MMS_ONLY_ENV }).ready;
    expect(atAttach).toBe(true);
  });

  it('detach destroys the instance; the next attach happens after the old destroy', async () => {
    const first = attach();
    await first.ready;
    first.detach();
    expect(FakeHls.instances[0]?.destroyed).toBe(true);
    expect(video.hasAttribute('src')).toBe(false);
    await attach({ response: f.playbackHls({ session_id: 'sess-2' }) }).ready;
    expect(hlsLog).toEqual(['new#1', 'loadSource#1', 'attachMedia#1', 'destroy#1', 'new#2', 'loadSource#2', 'attachMedia#2']);
  });

  it('a detach before the lazy import resolves never touches the element', async () => {
    let resolve!: (m: { default: typeof FakeHls }) => void;
    const loadHls = () => new Promise<{ default: typeof FakeHls }>((r) => (resolve = r));
    const handle = attach({ loadHls });
    handle.detach();
    resolve({ default: FakeHls });
    await handle.ready;
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('an aborted signal stops the async attach', async () => {
    const controller = new AbortController();
    const handle = attach({ signal: controller.signal });
    controller.abort();
    await handle.ready;
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('an undefined module (a reload is under way) does nothing', async () => {
    const handle = attach({ loadHls: () => Promise.resolve(undefined) });
    await handle.ready;
    expect(FakeHls.instances).toHaveLength(0);
    expect(signals).toEqual([]);
  });

  it('a chunk-load failure reloads once, then reports APP_UPDATED', async () => {
    const reload = vi.spyOn(reloader, 'reload').mockImplementation(() => undefined);
    const chunkError = () => Promise.reject(new TypeError('Failed to fetch dynamically imported module: /assets/hls-abc.js'));
    await attach({ loadHls: chunkError }).ready;
    expect(reload).toHaveBeenCalledTimes(1);
    expect(signals).toEqual([]);
    await attach({ loadHls: chunkError }).ready;
    expect(reload).toHaveBeenCalledTimes(1);
    expect(signals).toEqual([{ type: 'APP_UPDATED' }]);
  });

  it('fatal NETWORK_ERROR → FATAL network; non-fatal errors are ignored', async () => {
    const handle = attach();
    await handle.ready;
    FakeHls.last?.emit(FakeHls.Events.ERROR, { type: FakeHls.ErrorTypes.NETWORK_ERROR, fatal: false });
    expect(signals).toEqual([]);
    FakeHls.last?.fatal();
    expect(signals).toEqual([{ type: 'FATAL', source: 'hls', kind: 'network' }]);
  });

  it('fatal MEDIA_ERROR → one recoverMediaError(), then FATAL network', async () => {
    await attach().ready;
    const instance = FakeHls.last;
    instance?.fatal(FakeHls.ErrorTypes.MEDIA_ERROR);
    expect(instance?.recoverMediaErrorCalls).toBe(1);
    expect(signals).toEqual([]);
    instance?.fatal(FakeHls.ErrorTypes.MEDIA_ERROR);
    expect(instance?.recoverMediaErrorCalls).toBe(1);
    expect(signals).toEqual([{ type: 'FATAL', source: 'hls', kind: 'network' }]);
  });

  it('element error events are left to hls.js', async () => {
    await attach().ready;
    mediaState(video).error = { code: 3 };
    fire('error');
    expect(signals).toEqual([]);
  });

  it('events after detach are ignored', async () => {
    const handle = attach();
    await handle.ready;
    const instance = FakeHls.last;
    handle.detach();
    instance?.fatal();
    fire('playing');
    await tick();
    expect(signals).toEqual([]);
  });
});

describe('attachSource: direct', () => {
  it('sets src with the token and seeks + plays on loadedmetadata', () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const handle = attach({ response: direct, startPosition: 120 });
    expect(handle.kind).toBe('direct');
    expect(video.getAttribute('src')).toBe('/api/media-files/91/stream?token=tok%20en');
    fire('loadedmetadata');
    expect(video.currentTime).toBe(120);
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('direct mode never sets disableRemotePlayback', () => {
    attach({ response: direct, mediaSourceEnv: MMS_ONLY_ENV });
    expect(video.disableRemotePlayback).not.toBe(true);
  });

  it.each([
    [2, 'network'],
    [3, 'decode'],
    [4, 'decode'],
  ])('video.error code %s → FATAL %s', (code, kind) => {
    attach({ response: direct });
    mediaState(video).error = { code };
    fire('error');
    expect(signals).toEqual([{ type: 'FATAL', source: 'direct', kind }]);
  });

  it('detach resets src and removes the listeners (the queued timeupdate is not forwarded)', async () => {
    const onMediaEvent = vi.fn();
    const handle = attach({ response: direct, onMediaEvent });
    handle.detach();
    expect(video.hasAttribute('src')).toBe(false);
    await tick();
    fire('playing');
    expect(onMediaEvent).not.toHaveBeenCalled();
    expect(signals).toEqual([]);
  });
});

describe('attachSource: common', () => {
  it.each([
    ['native', IOS, hls],
    ['hls', DESKTOP, hls],
    ['direct', DESKTOP, direct],
  ] as const)('%s: playsinline and preload=metadata are set', async (_kind, platform, response) => {
    await attach({ platform, response }).ready;
    expect(video.hasAttribute('playsinline')).toBe(true);
    expect(video.getAttribute('preload')).toBe('metadata');
  });

  it('forwards element events: tracking first, then the machine signal', () => {
    const order: string[] = [];
    attach({
      response: direct,
      onMediaEvent: (type) => order.push(`media:${type}`),
      onSignal: (s) => order.push(`signal:${s.type}`),
    });
    for (const type of ['playing', 'pause', 'waiting', 'ended', 'timeupdate', 'seeked']) fire(type);
    expect(order).toEqual([
      'media:playing', 'signal:PLAYING',
      'media:pause', 'signal:PAUSE',
      'signal:WAITING',
      'media:ended', 'signal:ENDED',
      'media:timeupdate',
      'media:seeked',
    ]);
  });

  it('CAN_PLAY only when the element is actually playing', () => {
    attach({ response: direct });
    fire('canplay');
    expect(signals).toEqual([]);
    mediaState(video).paused = false;
    fire('canplay');
    expect(signals).toEqual([{ type: 'CAN_PLAY' }]);
  });
});

describe('play() rejections (§9 step 5a)', () => {
  it('NotAllowedError → PLAY_BLOCKED', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    attach({ response: direct });
    fire('loadedmetadata');
    await tick();
    expect(signals).toEqual([{ type: 'PLAY_BLOCKED' }]);
  });

  it('AbortError dispatches nothing', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'AbortError'));
    attach({ response: direct });
    fire('loadedmetadata');
    await tick();
    expect(signals).toEqual([]);
  });

  it('any other rejection is a media error', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotSupportedError'));
    attach({ response: direct });
    fire('loadedmetadata');
    await tick();
    expect(signals).toEqual([{ type: 'FATAL', source: 'direct', kind: 'decode' }]);
  });

  it('a rejection after detach is ignored', async () => {
    let reject!: (e: unknown) => void;
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockReturnValueOnce(new Promise((_, r) => (reject = r)));
    const handle = attach({ response: direct });
    fire('loadedmetadata');
    handle.detach();
    reject(new DOMException('', 'NotAllowedError'));
    await tick();
    expect(signals).toEqual([]);
  });

  it('playMedia applies the same rules to the app’s own play action', async () => {
    const onSignal = vi.fn();
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('', 'NotAllowedError'));
    await playMedia(video, onSignal, 'direct');
    expect(onSignal).toHaveBeenCalledWith({ type: 'PLAY_BLOCKED' });
  });
});
