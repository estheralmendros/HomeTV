import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ClientCaps } from '@/api/types';
import {
  MIME,
  UA,
  androidChromeEnv,
  chromeEnv,
  fakeEnv,
  firefoxEnv,
  ipadDesktopEnv,
  iphoneEnv,
  safariMacEnv,
} from '../../tests/capsEnvs';
import { detectClientCaps, detectPlatform, getClientCaps, getPlatform, setCapabilityEnv, type CapsEnv } from './capabilities';

// The module must never pull hls.js in (caps are computed on detail pages too).
vi.mock('hls.js', () => {
  throw new Error('capabilities.ts must not import hls.js');
});

function caps(overrides: Partial<ClientCaps>): ClientCaps {
  return {
    containers: [],
    video_codecs: [],
    audio_codecs: [],
    max_audio_channels: 6,
    hls: false,
    can_select_audio_track: false,
    ...overrides,
  };
}

const CHROME_CAPS = caps({
  containers: ['mp4', 'webm'],
  video_codecs: ['h264', 'vp9', 'av1'],
  audio_codecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
  hls: true,
});

describe('detectClientCaps / detectPlatform', () => {
  const table: Array<{ name: string; env: () => CapsEnv; caps: ClientCaps; platform: ReturnType<typeof detectPlatform> }> = [
    { name: 'Chrome desktop', env: chromeEnv, caps: CHROME_CAPS, platform: { ios: false, nativeHls: false, mse: true } },
    { name: 'Firefox desktop', env: firefoxEnv, caps: CHROME_CAPS, platform: { ios: false, nativeHls: false, mse: true } },
    {
      name: 'Safari macOS (hls.js path although m3u8 is "maybe")',
      env: safariMacEnv,
      caps: caps({
        containers: ['mp4'],
        video_codecs: ['h264', 'hevc'],
        audio_codecs: ['aac', 'mp3', 'ac3', 'eac3'],
        hls: true,
      }),
      platform: { ios: false, nativeHls: false, mse: true },
    },
    {
      name: 'iPhone (codecs from canPlayType, not ManagedMediaSource; no webm)',
      env: iphoneEnv,
      caps: caps({ containers: ['mp4'], video_codecs: ['h264', 'hevc'], audio_codecs: ['aac', 'ac3', 'eac3'], hls: true }),
      platform: { ios: true, nativeHls: true, mse: true },
    },
    {
      name: 'iPadOS desktop mode (Mac UA + touch points)',
      env: ipadDesktopEnv,
      caps: caps({ containers: ['mp4'], video_codecs: ['h264', 'hevc'], audio_codecs: ['aac', 'ac3', 'eac3'], hls: true }),
      platform: { ios: true, nativeHls: true, mse: false },
    },
    {
      name: 'Mac UA without touch points',
      env: () => fakeEnv({ userAgent: UA.safariMac, maxTouchPoints: 0, mse: null, native: [MIME.m3u8, MIME.mp4, MIME.h264] }),
      caps: caps({ containers: ['mp4'], video_codecs: ['h264'], hls: false }),
      platform: { ios: false, nativeHls: false, mse: false },
    },
    {
      name: 'Android Chrome (m3u8 "maybe" but hls.js path)',
      env: androidChromeEnv,
      caps: CHROME_CAPS,
      platform: { ios: false, nativeHls: false, mse: true },
    },
    {
      name: 'HEVC Main only → hevc not claimed',
      env: () => fakeEnv({ userAgent: UA.chrome, mse: [MIME.mseProbe, MIME.h264, MIME.hevcMain, MIME.aac], native: [MIME.mp4] }),
      caps: caps({ containers: ['mp4'], video_codecs: ['h264'], audio_codecs: ['aac'], hls: true }),
      platform: { ios: false, nativeHls: false, mse: true },
    },
    {
      name: 'VP9 and Opus only in WebM → not claimed',
      env: () =>
        fakeEnv({
          userAgent: UA.chrome,
          mse: [MIME.mseProbe, MIME.h264, MIME.aac, MIME.vp9Webm, MIME.opusWebm],
          native: [MIME.mp4, MIME.webm, MIME.vp9Webm, MIME.opusWebm],
        }),
      caps: caps({ containers: ['mp4', 'webm'], video_codecs: ['h264'], audio_codecs: ['aac'], hls: true }),
      platform: { ios: false, nativeHls: false, mse: true },
    },
    {
      name: 'no MSE and no native HLS → hls=false (codecs from canPlayType)',
      env: () => fakeEnv({ userAgent: UA.firefox, mse: null, native: [MIME.mp4, MIME.h264, MIME.aac] }),
      caps: caps({ containers: ['mp4'], video_codecs: ['h264'], audio_codecs: ['aac'], hls: false }),
      platform: { ios: false, nativeHls: false, mse: false },
    },
  ];

  it.each(table)('$name', ({ env, caps: expected, platform }) => {
    expect(detectClientCaps(env())).toEqual(expected);
    expect(detectPlatform(env())).toEqual(platform);
  });

  it('accepts any of the three MP3-in-MP4 strings', () => {
    for (const mime of [MIME.mp3_6B, MIME.mp3_69, MIME.mp3]) {
      const env = fakeEnv({ userAgent: UA.chrome, mse: [MIME.mseProbe, mime], native: [MIME.mp4] });
      expect(detectClientCaps(env).audio_codecs).toEqual(['mp3']);
    }
  });

  it('never claims mkv, ts or mov, and never claims audio track selection', () => {
    const env = fakeEnv({
      userAgent: UA.chrome,
      mse: [MIME.mseProbe, MIME.h264],
      native: [MIME.mp4, MIME.webm, MIME.mkv, MIME.ts, MIME.mov],
    });
    const result = detectClientCaps(env);
    expect(result.containers).toEqual(['mp4', 'webm']);
    expect(result.can_select_audio_track).toBe(false);
    expect(result.max_audio_channels).toBe(6);
  });

  it('sends an empty video codec list on a very old browser', () => {
    const env = fakeEnv({ userAgent: UA.firefox, mse: null, native: [] });
    expect(detectClientCaps(env)).toEqual(caps({}));
  });
});

describe('memoized getClientCaps / getPlatform', () => {
  afterEach(() => setCapabilityEnv(chromeEnv()));

  it('detects once per env and re-detects after setCapabilityEnv', () => {
    const env = chromeEnv();
    const spy = vi.fn(env.canPlayType);
    setCapabilityEnv({ ...env, canPlayType: spy });
    const first = getClientCaps();
    const calls = spy.mock.calls.length;
    expect(getClientCaps()).toBe(first);
    expect(spy.mock.calls.length).toBe(calls);

    setCapabilityEnv(iphoneEnv());
    expect(getClientCaps().video_codecs).toEqual(['h264', 'hevc']);
    expect(getPlatform()).toEqual({ ios: true, nativeHls: true, mse: true });
  });
});
