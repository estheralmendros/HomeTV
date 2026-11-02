// WebVTT loader: fetch with the Bearer header (no ?token=), retry
// `503 subtitle_pending` after Retry-After (fallback 10 s) for up to 5 min, then attach as a
// blob: URL. Blob URLs are cached per page keyed by `${media_file_id}:${track.id}` (track ids
// such as `emb-3` repeat across files) and revoked when the file changes or on dispose.
import { retryDelaySeconds } from '@/api/client';
import { isApiError } from '@/api/errors';
import { getSubtitleText } from '@/api/playback';
import type { SubtitleTrack } from '@/api/types';
import { S } from '@/strings.es';

export type SubtitleLoadResult =
  | { status: 'loaded'; url: string }
  | { status: 'failed'; message: string }
  | { status: 'aborted' };

export interface SubtitleLoaderOptions {
  fetchText?: (url: string, signal: AbortSignal) => Promise<string>;
  /** Give up after this long in `subtitle_pending` (default 5 min). */
  pendingLimitMs?: number;
}

export interface LoadOptions {
  signal?: AbortSignal;
  /** Called on each `subtitle_pending` answer (the menu shows "Preparando…"). */
  onPending?: () => void;
}

const PENDING_FALLBACK_S = 10;
const cacheKey = (fileId: number, trackId: string) => `${fileId}:${trackId}`;

class Aborted extends Error {}

/** Resolves after `ms`, rejects with Aborted when `signal` aborts first. */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new Aborted());
    const onAbort = () => {
      clearTimeout(timer);
      reject(new Aborted());
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/** A signal that aborts when any of the inputs aborts (AbortSignal.any is not everywhere). */
function anySignal(signals: Array<AbortSignal | undefined>): AbortSignal {
  const controller = new AbortController();
  for (const signal of signals) {
    if (!signal) continue;
    if (signal.aborted) {
      controller.abort();
      break;
    }
    signal.addEventListener('abort', () => controller.abort(), { once: true });
  }
  return controller.signal;
}

function terminalMessage(err: unknown): string {
  if (isApiError(err, 'subtitle_extraction_failed') || isApiError(err, 'subtitle_timeout')) {
    return S.errors.byCode[err.code] as string;
  }
  return S.player.subtitles.loadFailed;
}

export class SubtitleLoader {
  private readonly fetchText: (url: string, signal: AbortSignal) => Promise<string>;
  private readonly pendingLimitMs: number;
  private readonly urls = new Map<string, { fileId: number; url: string }>();
  private readonly disposed = new AbortController();

  constructor({ fetchText = getSubtitleText, pendingLimitMs = 5 * 60_000 }: SubtitleLoaderOptions = {}) {
    this.fetchText = fetchText;
    this.pendingLimitMs = pendingLimitMs;
  }

  /** The cached blob URL of a track, if loaded. */
  cached(fileId: number, trackId: string): string | null {
    return this.urls.get(cacheKey(fileId, trackId))?.url ?? null;
  }

  async load(fileId: number, track: SubtitleTrack, { signal, onPending }: LoadOptions = {}): Promise<SubtitleLoadResult> {
    const hit = this.cached(fileId, track.id);
    if (hit) return { status: 'loaded', url: hit };
    if (!track.url) return { status: 'failed', message: S.player.subtitles.loadFailed };

    const abort = anySignal([signal, this.disposed.signal]);
    let pendingSince: number | null = null;
    for (;;) {
      if (abort.aborted) return { status: 'aborted' };
      try {
        const text = await this.fetchText(track.url, abort);
        if (abort.aborted) return { status: 'aborted' };
        const url = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
        this.urls.set(cacheKey(fileId, track.id), { fileId, url });
        return { status: 'loaded', url };
      } catch (err) {
        if (abort.aborted) return { status: 'aborted' };
        if (!isApiError(err, 'subtitle_pending')) return { status: 'failed', message: terminalMessage(err) };
        const now = Date.now();
        pendingSince ??= now;
        if (now - pendingSince >= this.pendingLimitMs) {
          return { status: 'failed', message: S.player.subtitles.pendingGaveUp };
        }
        onPending?.();
        try {
          await sleep(retryDelaySeconds(err, PENDING_FALLBACK_S) * 1000, abort);
        } catch {
          return { status: 'aborted' };
        }
      }
    }
  }

  /** The current file changed (PLAY_FILE): revoke and forget every other file's blob URLs. */
  setFile(fileId: number): void {
    for (const [key, entry] of this.urls) {
      if (entry.fileId === fileId) continue;
      URL.revokeObjectURL(entry.url);
      this.urls.delete(key);
    }
  }

  /** Unmount: abort pending loads and revoke every blob URL. */
  dispose(): void {
    this.disposed.abort();
    for (const { url } of this.urls.values()) URL.revokeObjectURL(url);
    this.urls.clear();
  }
}
