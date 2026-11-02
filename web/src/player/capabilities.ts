// Client capability detection. Pure with respect to the injected env, so tests pass
// fakes. hls.js is never imported here: `mse` performs the same probe as `Hls.isSupported()`,
// so detail pages can compute version defaults without loading the player chunk.
import type { ClientCaps } from '@/api/types';

export interface CapsEnv {
  userAgent: string;
  maxTouchPoints: number;
  /** `MediaSource.isTypeSupported` (or ManagedMediaSource's); undefined without MSE. */
  isTypeSupported?: (mime: string) => boolean;
  /** `HTMLMediaElement.canPlayType` of a scratch <video>. */
  canPlayType: (mime: string) => string;
}

export interface Platform {
  /** iOS / iPadOS (any browser there is WebKit). */
  ios: boolean;
  /** Play HLS natively through `video.src` (always on iOS/iPadOS, never elsewhere). */
  nativeHls: boolean;
  /** MediaSource can play fMP4 H.264 + AAC (hls.js would work). */
  mse: boolean;
}

const MSE_PROBE = 'video/mp4; codecs="avc1.42E01E,mp4a.40.2"';
const HLS_MIME = 'application/vnd.apple.mpegurl';

/** Codec → the fMP4 MIME strings that must ALL be supported (the server's remux output). */
const VIDEO_CODECS: ReadonlyArray<[codec: string, mimes: readonly string[]]> = [
  ['h264', ['video/mp4; codecs="avc1.640028"']],
  // HEVC Main and Main 10 (HDR libraries are mostly 10-bit).
  ['hevc', ['video/mp4; codecs="hvc1.1.6.L150.90"', 'video/mp4; codecs="hvc1.2.4.L153.B0"']],
  ['vp9', ['video/mp4; codecs="vp09.00.40.08"']],
  ['av1', ['video/mp4; codecs="av01.0.08M.10"']],
];

/** Codec → MIME strings of which ANY must be supported. */
const AUDIO_CODECS: ReadonlyArray<[codec: string, mimes: readonly string[]]> = [
  ['aac', ['audio/mp4; codecs="mp4a.40.2"']],
  ['mp3', ['audio/mp4; codecs="mp4a.6B"', 'audio/mp4; codecs="mp4a.69"', 'audio/mp4; codecs="mp3"']],
  ['opus', ['audio/mp4; codecs="opus"']],
  ['flac', ['audio/mp4; codecs="flac"']],
  ['ac3', ['audio/mp4; codecs="ac-3"']],
  ['eac3', ['audio/mp4; codecs="ec-3"']],
];

const canPlay = (env: CapsEnv, mime: string) => env.canPlayType(mime) !== '';

export function detectPlatform(env: CapsEnv): Platform {
  const appleMobileUa = /iPhone|iPad|iPod/.test(env.userAgent) || (/Macintosh/.test(env.userAgent) && env.maxTouchPoints > 1);
  const ios = appleMobileUa && canPlay(env, HLS_MIME);
  const mse = env.isTypeSupported?.(MSE_PROBE) === true;
  return { ios, nativeHls: ios, mse };
}

export function detectClientCaps(env: CapsEnv = browserEnv()): ClientCaps {
  const platform = detectPlatform(env);
  const { isTypeSupported } = env;
  // iOS plays natively (no MSE); elsewhere MSE is the oracle when it exists.
  const supports = (mime: string): boolean =>
    platform.ios || !isTypeSupported ? canPlay(env, mime) : isTypeSupported(mime);

  const containers: string[] = [];
  if (canPlay(env, 'video/mp4')) containers.push('mp4');
  if (!platform.ios && canPlay(env, 'video/webm')) containers.push('webm');

  const video_codecs = VIDEO_CODECS.filter(([, mimes]) => mimes.every(supports)).map(([codec]) => codec);
  const audio_codecs = AUDIO_CODECS.filter(([, mimes]) => mimes.some(supports)).map(([codec]) => codec);
  // Vorbis is never copied into fMP4 (the server re-encodes it for HLS): WebM direct play only.
  if (canPlay(env, 'audio/webm; codecs="vorbis"')) audio_codecs.push('vorbis');

  return {
    containers,
    video_codecs,
    audio_codecs,
    max_audio_channels: 6,
    hls: platform.nativeHls || platform.mse,
    can_select_audio_track: false,
  };
}

type MediaSourceLike = { isTypeSupported?: (mime: string) => boolean };

/** The real browser env: navigator + a scratch <video> + MediaSource ?? ManagedMediaSource. */
export function browserEnv(): CapsEnv {
  const video = document.createElement('video');
  const w = window as unknown as { MediaSource?: MediaSourceLike; ManagedMediaSource?: MediaSourceLike };
  const source = w.MediaSource ?? w.ManagedMediaSource;
  return {
    userAgent: navigator.userAgent,
    maxTouchPoints: navigator.maxTouchPoints ?? 0,
    isTypeSupported: typeof source?.isTypeSupported === 'function' ? source.isTypeSupported.bind(source) : undefined,
    canPlayType: (mime) => video.canPlayType(mime),
  };
}

let envOverride: CapsEnv | null = null;
let memo: { caps: ClientCaps; platform: Platform } | null = null;

function detected() {
  if (!memo) {
    const env = envOverride ?? browserEnv();
    memo = { caps: detectClientCaps(env), platform: detectPlatform(env) };
  }
  return memo;
}

/** `detectClientCaps()` computed once per page load. */
export function getClientCaps(): ClientCaps {
  return detected().caps;
}

/** `detectPlatform()` computed once per page load. */
export function getPlatform(): Platform {
  return detected().platform;
}

/** Test seam: detect from `env` instead of the browser (null → the browser again). Clears the memo. */
export function setCapabilityEnv(env: CapsEnv | null): void {
  envOverride = env;
  memo = null;
}
