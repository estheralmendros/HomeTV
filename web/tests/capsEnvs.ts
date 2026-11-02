// Fake browser environments for player/capabilities.ts. Each env
// answers only for the exact MIME strings it lists, so a probe with a different string fails.
// tests/setup.ts installs chromeEnv() as the default env for every test; player tests switch
// with `setCapabilityEnv(iphoneEnv())`.
import type { CapsEnv } from '@/player/capabilities';

export const MIME = {
  mseProbe: 'video/mp4; codecs="avc1.42E01E,mp4a.40.2"',
  m3u8: 'application/vnd.apple.mpegurl',
  mp4: 'video/mp4',
  webm: 'video/webm',
  h264: 'video/mp4; codecs="avc1.640028"',
  hevcMain: 'video/mp4; codecs="hvc1.1.6.L150.90"',
  hevcMain10: 'video/mp4; codecs="hvc1.2.4.L153.B0"',
  vp9: 'video/mp4; codecs="vp09.00.40.08"',
  vp9Webm: 'video/webm; codecs="vp9"',
  av1: 'video/mp4; codecs="av01.0.08M.10"',
  aac: 'audio/mp4; codecs="mp4a.40.2"',
  mp3_6B: 'audio/mp4; codecs="mp4a.6B"',
  mp3_69: 'audio/mp4; codecs="mp4a.69"',
  mp3: 'audio/mp4; codecs="mp3"',
  opus: 'audio/mp4; codecs="opus"',
  opusWebm: 'audio/webm; codecs="opus"',
  flac: 'audio/mp4; codecs="flac"',
  ac3: 'audio/mp4; codecs="ac-3"',
  eac3: 'audio/mp4; codecs="ec-3"',
  vorbisWebm: 'audio/webm; codecs="vorbis"',
  mkv: 'video/x-matroska',
  ts: 'video/mp2t',
  mov: 'video/quicktime',
} as const;

export const UA = {
  chrome:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
  firefox: 'Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0',
  safariMac:
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
  iphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1',
  android:
    'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36',
} as const;

export interface FakeEnvSpec {
  userAgent: string;
  maxTouchPoints?: number;
  /** MIME strings MediaSource.isTypeSupported accepts; null = no MediaSource at all. */
  mse: readonly string[] | null;
  /** MIME strings canPlayType answers "maybe" for (everything else: ""). */
  native: readonly string[];
}

export function fakeEnv({ userAgent, maxTouchPoints = 0, mse, native }: FakeEnvSpec): CapsEnv {
  const mseSet = mse ? new Set(mse) : null;
  const nativeSet = new Set(native);
  return {
    userAgent,
    maxTouchPoints,
    isTypeSupported: mseSet ? (mime: string) => mseSet.has(mime) : undefined,
    canPlayType: (mime: string) => (nativeSet.has(mime) ? 'maybe' : ''),
  };
}

const CHROME_MSE = [MIME.mseProbe, MIME.h264, MIME.vp9, MIME.av1, MIME.aac, MIME.opus, MIME.flac, MIME.mp3_6B];
const DESKTOP_NATIVE = [MIME.mp4, MIME.webm, MIME.vorbisWebm, MIME.vp9Webm, MIME.opusWebm];

/** Desktop Chrome: MSE with avc1/vp09/av01/mp4a/opus/flac/mp4a.6B in MP4, no HEVC. */
export function chromeEnv(): CapsEnv {
  return fakeEnv({ userAgent: UA.chrome, mse: CHROME_MSE, native: DESKTOP_NATIVE });
}

export function firefoxEnv(): CapsEnv {
  return fakeEnv({ userAgent: UA.firefox, mse: CHROME_MSE, native: DESKTOP_NATIVE });
}

/** Safari macOS: MSE including HEVC Main + Main 10, AC-3 and E-AC-3; native m3u8 too. */
export function safariMacEnv(): CapsEnv {
  return fakeEnv({
    userAgent: UA.safariMac,
    mse: [MIME.mseProbe, MIME.h264, MIME.hevcMain, MIME.hevcMain10, MIME.aac, MIME.mp3_69, MIME.ac3, MIME.eac3],
    native: [MIME.mp4, MIME.m3u8],
  });
}

const IOS_NATIVE = [MIME.m3u8, MIME.mp4, MIME.webm, MIME.h264, MIME.hevcMain, MIME.hevcMain10, MIME.aac, MIME.ac3, MIME.eac3];

/**
 * iPhone: no MediaSource; ManagedMediaSource present (it accepts everything here, so a codec
 * list taken from it instead of canPlayType would be wrong).
 */
export function iphoneEnv(): CapsEnv {
  const env = fakeEnv({ userAgent: UA.iphone, maxTouchPoints: 5, mse: null, native: IOS_NATIVE });
  return { ...env, isTypeSupported: () => true };
}

/** iPadOS in desktop mode: a Mac user agent with touch points. */
export function ipadDesktopEnv(): CapsEnv {
  return fakeEnv({ userAgent: UA.safariMac, maxTouchPoints: 5, mse: null, native: IOS_NATIVE });
}

export function androidChromeEnv(): CapsEnv {
  return fakeEnv({ userAgent: UA.android, maxTouchPoints: 5, mse: CHROME_MSE, native: [...DESKTOP_NATIVE, MIME.m3u8] });
}
