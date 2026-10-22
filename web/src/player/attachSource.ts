// Attaches a playback decision to the shell's single <video> (prd2 §9 step 5, Player identity):
// - direct  → `video.src = withToken(url)`, seek + play() on loadedmetadata;
// - hls on iOS/iPadOS (platform.nativeHls) → native HLS: `video.src = url` (no token), seek on
//   loadedmetadata (once more on canplay when > 1 s off), then play(); hls.js is never loaded;
// - hls elsewhere → lazy `import('hls.js')`, `new Hls({...HLS_CONFIG, startPosition})`,
//   loadSource(url) (no token), attachMedia(video), play() on MANIFEST_PARSED.
// `detach()` releases the element synchronously: listeners removed, then `hls.destroy()` or
// `removeAttribute('src'); load()`. The async attach checks for a detach/abort after every await,
// so a new Hls instance attaches only after the previous instance was destroyed.
import type { HlsConfig } from 'hls.js';
import { withToken } from '@/api/urls';
import { isChunkLoadError, reloadOnce } from '@/lib/chunkReload';
import type { Platform } from './capabilities';
import type { MediaErrorKind, MediaErrorSource } from './playerMachine';

/** hls.js ≥ 1.5 load policies (prd2 Technical Requirements): segments up to 30 s, 20 s init.mp4 wait. */
export const HLS_CONFIG = {
  enableWorker: true,
  lowLatencyMode: false,
  preferManagedMediaSource: false,
  maxBufferLength: 90,
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
} satisfies Partial<HlsConfig>;

/** Native HLS: a `waiting` longer than this with no `progress` event is a network failure. */
export const NATIVE_STALL_MS = 45_000;

/** What the attached source tells the player (mapped to machine events by usePlaybackSession). */
export type MediaSignal =
  | { type: 'PLAYING' | 'PAUSE' | 'WAITING' | 'ENDED' | 'CAN_PLAY' | 'PLAY_BLOCKED' }
  | { type: 'FATAL'; source: MediaErrorSource; kind: Exclude<MediaErrorKind, 'gone'> }
  /** The hls.js chunk failed and reloadOnce() gave up: show the "app was updated" screen. */
  | { type: 'APP_UPDATED' };

/** Element events that update the tracked position (useProgressReporter's PositionTracker). */
export type TrackedMediaEvent = 'timeupdate' | 'seeked' | 'pause' | 'ended' | 'playing';

// Minimal structural view of hls.js, so tests can inject tests/fakeHls.ts.
interface HlsInstance {
  on(event: string, handler: (event: string, data: unknown) => void): void;
  loadSource(url: string): void;
  attachMedia(media: HTMLMediaElement): void;
  recoverMediaError(): void;
  destroy(): void;
}
interface HlsConstructor {
  new (config: Record<string, unknown>): HlsInstance;
  Events: { MANIFEST_PARSED: string; ERROR: string };
  ErrorTypes: { NETWORK_ERROR: string; MEDIA_ERROR: string };
}
export type HlsModule = { default: HlsConstructor };

const importHls = () => import('hls.js') as unknown as Promise<HlsModule | undefined>;

export interface MediaSourceEnv {
  MediaSource?: unknown;
  ManagedMediaSource?: unknown;
}

/** The part of a `direct` / `hls` playback response the attach needs. */
export interface AttachTarget {
  mode: 'direct' | 'hls';
  url: string;
}

export interface AttachOptions {
  video: HTMLVideoElement;
  response: AttachTarget;
  startPosition: number;
  platform: Platform;
  onSignal: (signal: MediaSignal) => void;
  onMediaEvent?: (type: TrackedMediaEvent) => void;
  /** The mount's signal: once aborted, the async attach never touches the element. */
  signal?: AbortSignal;
  /** Test seam for the lazy hls.js chunk. */
  loadHls?: () => Promise<HlsModule | undefined>;
  /** Test seam: `window` by default (MediaSource / ManagedMediaSource presence). */
  mediaSourceEnv?: MediaSourceEnv;
}

export type AttachKind = 'direct' | 'native' | 'hls';

export interface AttachHandle {
  readonly kind: AttachKind;
  readonly detached: boolean;
  /** Settles when the (possibly async) attach finished or was abandoned. */
  readonly ready: Promise<void>;
  /** Synchronous release of the element (Player identity hand-over). */
  detach(): void;
}

/**
 * Awaits `video.play()` (§9 step 5a): NotAllowedError → PLAY_BLOCKED, AbortError ignored,
 * NotSupportedError → decode failure, anything else → network failure (recovery).
 */
export async function playMedia(
  video: HTMLMediaElement,
  onSignal: (signal: MediaSignal) => void,
  source: MediaErrorSource,
  isStale: () => boolean = () => false,
): Promise<void> {
  try {
    await video.play();
  } catch (err) {
    if (isStale()) return;
    const name = (err as { name?: unknown } | null)?.name;
    if (name === 'AbortError') return;
    if (name === 'NotAllowedError') onSignal({ type: 'PLAY_BLOCKED' });
    else onSignal({ type: 'FATAL', source, kind: name === 'NotSupportedError' ? 'decode' : 'network' });
  }
}

const ELEMENT_EVENTS = [
  'loadedmetadata', 'canplay', 'playing', 'pause', 'waiting', 'ended', 'timeupdate', 'seeked', 'error', 'progress',
] as const;

export function attachSource(options: AttachOptions): AttachHandle {
  const { video, response, startPosition, platform, onSignal, onMediaEvent, signal } = options;
  const kind: AttachKind = response.mode === 'direct' ? 'direct' : platform.nativeHls ? 'native' : 'hls';
  const errorSource: MediaErrorSource = kind;

  let detached = false;
  let hls: HlsInstance | null = null;
  let stallTimer: ReturnType<typeof setTimeout> | null = null;
  let resyncPending = kind === 'native';
  const stale = () => detached || signal?.aborted === true;
  const signalIfLive = (s: MediaSignal) => {
    if (!stale()) onSignal(s);
  };
  const play = () => void playMedia(video, onSignal, errorSource, stale);

  const clearStall = () => {
    if (stallTimer !== null) clearTimeout(stallTimer);
    stallTimer = null;
  };
  const startStall = () => {
    clearStall();
    stallTimer = setTimeout(() => signalIfLive({ type: 'FATAL', source: 'native', kind: 'network' }), NATIVE_STALL_MS);
  };

  const onElementEvent = (event: Event) => {
    if (stale()) return;
    switch (event.type) {
      case 'loadedmetadata':
        if (kind !== 'hls') {
          video.currentTime = startPosition;
          play();
        }
        return;
      case 'canplay':
        if (resyncPending) {
          resyncPending = false;
          if (Math.abs(video.currentTime - startPosition) > 1) video.currentTime = startPosition;
        }
        if (!video.paused) onSignal({ type: 'CAN_PLAY' });
        return;
      case 'playing':
        clearStall();
        onMediaEvent?.('playing');
        onSignal({ type: 'PLAYING' });
        return;
      case 'pause':
        clearStall();
        onMediaEvent?.('pause');
        onSignal({ type: 'PAUSE' });
        return;
      case 'waiting':
        if (kind === 'native') startStall();
        onSignal({ type: 'WAITING' });
        return;
      case 'progress':
        if (stallTimer !== null) startStall();
        return;
      case 'ended':
        clearStall();
        onMediaEvent?.('ended');
        onSignal({ type: 'ENDED' });
        return;
      case 'timeupdate':
      case 'seeked':
        onMediaEvent?.(event.type);
        return;
      case 'error': {
        if (kind === 'hls') return; // hls.js reports its own errors
        const code = video.error?.code;
        if (code === 2) onSignal({ type: 'FATAL', source: errorSource, kind: 'network' });
        else if (code === 3) onSignal({ type: 'FATAL', source: errorSource, kind: kind === 'native' ? 'network' : 'decode' });
        else if (code === 4) onSignal({ type: 'FATAL', source: errorSource, kind: 'decode' });
        return;
      }
    }
  };

  video.setAttribute('playsinline', '');
  video.setAttribute('preload', 'metadata');
  for (const type of ELEMENT_EVENTS) video.addEventListener(type, onElementEvent);

  let ready: Promise<void> = Promise.resolve();
  if (kind === 'direct') {
    video.src = withToken(response.url);
  } else if (kind === 'native') {
    video.src = response.url;
  } else {
    ready = attachHlsJs();
  }

  async function attachHlsJs(): Promise<void> {
    let module: HlsModule | undefined;
    try {
      module = await (options.loadHls ?? importHls)();
    } catch (err) {
      if (stale()) return;
      if (isChunkLoadError(err)) {
        if (reloadOnce() === 'gave_up') onSignal({ type: 'APP_UPDATED' });
      } else {
        onSignal({ type: 'FATAL', source: 'hls', kind: 'network' });
      }
      return;
    }
    if (stale() || !module?.default) return; // undefined module: a reload is under way (§13)
    const Hls = module.default;
    const env = options.mediaSourceEnv ?? (window as unknown as MediaSourceEnv);
    if (!env.MediaSource && env.ManagedMediaSource) video.disableRemotePlayback = true;

    const instance = new Hls({ ...HLS_CONFIG, startPosition });
    hls = instance;
    let mediaErrorRecovered = false;
    instance.on(Hls.Events.MANIFEST_PARSED, () => {
      if (!stale()) play();
    });
    instance.on(Hls.Events.ERROR, (_event, data) => {
      const { fatal, type } = (data ?? {}) as { fatal?: boolean; type?: string };
      if (!fatal || stale()) return;
      if (type === Hls.ErrorTypes.MEDIA_ERROR && !mediaErrorRecovered) {
        mediaErrorRecovered = true;
        instance.recoverMediaError();
        return;
      }
      onSignal({ type: 'FATAL', source: 'hls', kind: 'network' });
    });
    instance.loadSource(response.url);
    instance.attachMedia(video);
  }

  return {
    kind,
    ready,
    get detached() {
      return detached;
    },
    detach() {
      if (detached) return;
      detached = true;
      clearStall();
      for (const type of ELEMENT_EVENTS) video.removeEventListener(type, onElementEvent);
      if (kind === 'hls') {
        hls?.destroy();
        hls = null;
      } else {
        video.removeAttribute('src');
        video.load();
      }
    },
  };
}
