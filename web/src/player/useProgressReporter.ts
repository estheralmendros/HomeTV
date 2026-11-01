// Progress reporting (prd2 §9 step 9).
// - PositionTracker holds `lastKnownPosition`/`lastKnownDuration`, updated from the element only
//   while this mount's source is attached, guarded by attach generations (Rev 7) and by
//   `trackingLive` (tracking resumes once the new source reaches its start, Rev 8). Every final
//   report sends these tracked values, never the shared element's (it is reset on release).
// - useProgressReporter: `PUT /api/progress` every 10 s while playing, on pause, seeked
//   (debounced 1 s), ended (position = duration), pagehide / visibilitychange→hidden (keepalive)
//   and once on exit (final, registered in progressSync for the re-entry rule).
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { isApiError, logApiError } from '@/api/errors';
import type { PlayedItem } from '@/api/queryKeys';
import type { ProgressRequest, RatingPrompt } from '@/api/types';
import { putProgress } from '@/api/watch';
import type { TrackedMediaEvent } from './attachSource';
import { canReport, type PlayerStateName } from './playerMachine';
import { registerFinalReport } from './progressSync';

const finite = (n: number) => (Number.isFinite(n) ? n : null);

type TrackerListener = (type: TrackedMediaEvent) => void;

export class PositionTracker {
  /** lastKnownPosition (seconds). */
  position: number;
  /** lastKnownDuration: the playback response's, else the element's when finite. */
  duration: number | null;
  /** attachGen: incremented by every release. */
  gen = 0;
  attached = false;
  /** trackingLive: the current source reached its start. */
  live = false;
  private liveFrom = 0;
  private fixedDuration: boolean;
  private readonly listeners = new Set<TrackerListener>();

  constructor(position = 0, duration: number | null = null) {
    this.position = position;
    this.duration = duration;
    this.fixedDuration = duration != null;
  }

  /** A new source starts at `start`; returns the generation its events must carry. */
  beginAttach(start: number, { duration, resetPosition }: { duration: number | null; resetPosition: boolean }): number {
    this.attached = true;
    this.live = false;
    this.liveFrom = start;
    if (resetPosition) this.position = start;
    if (duration != null) {
      this.duration = duration;
      this.fixedDuration = true;
    } else if (resetPosition) {
      this.duration = null;
      this.fixedDuration = false;
    }
    return this.gen;
  }

  /** Conditional snapshot (Rev 8): only an attached, live source moves the tracked values. */
  snapshot(video: HTMLMediaElement): void {
    if (!this.attached || !this.live) return;
    this.position = video.currentTime;
    if (!this.fixedDuration) this.duration = finite(video.duration) ?? this.duration;
  }

  /** Every release (in-mount or unmount) runs this BEFORE hls.destroy() / load(). */
  release(video: HTMLMediaElement): void {
    if (!this.attached) return;
    this.snapshot(video);
    this.gen += 1;
    this.attached = false;
    this.live = false;
  }

  /** An element event of the source attached as generation `gen`. */
  handle(gen: number, type: TrackedMediaEvent, video: HTMLMediaElement): void {
    if (gen !== this.gen || !this.attached) return;
    if (!this.live) {
      if ((type !== 'seeked' && type !== 'playing') || video.currentTime < this.liveFrom - 1) return;
      this.live = true;
    }
    if (!this.fixedDuration) this.duration = finite(video.duration) ?? this.duration;
    this.position = type === 'ended' ? (this.duration ?? video.currentTime) : video.currentTime;
    for (const listener of this.listeners) listener(type);
  }

  subscribe(listener: TrackerListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export const REPORT_INTERVAL_MS = 10_000;
export const SEEK_REPORT_DEBOUNCE_MS = 1_000;

type ReportKind = 'periodic' | 'seek' | 'pause' | 'ended' | 'unload' | 'final';

export interface ProgressReporterOptions {
  item: PlayedItem;
  /** The current media file (null before the start decision). */
  fileId: number | null;
  stateName: PlayerStateName;
  /** The machine's `hasPlayed`: no pause/final report before the first frame. */
  hasPlayed: boolean;
  tracker: PositionTracker;
  /** The final report's `rating_prompt` (ratings prd2 FR-W9.1); read at send time. */
  onFinalPrompt?: (prompt: RatingPrompt) => void;
}

export interface ProgressReporter {
  /** A report answered `watched=true` (the UI shows ✓). */
  markedWatched: boolean;
  /** The latest non-null `rating_prompt` of a non-final report (a later null never clears it). */
  ratingPrompt: RatingPrompt | null;
  /** The explicit final report before a next-episode/next-part navigation (once per mount). */
  sendFinal: () => void;
}

export function useProgressReporter(options: ProgressReporterOptions): ProgressReporter {
  const queryClient = useQueryClient();
  const [markedWatched, setMarkedWatched] = useState(false);
  const [ratingPrompt, setRatingPrompt] = useState<RatingPrompt | null>(null);
  const latest = useRef(options);
  latest.current = options;
  const lastReported = useRef<number | null>(null);
  const stopped = useRef(false);
  const finalSent = useRef(false);

  /** Sends one report; returns whether it was sent. */
  const report = useCallback(
    (kind: ReportKind): boolean => {
      const { item, fileId, tracker, hasPlayed, stateName } = latest.current;
      if (stopped.current || fileId == null) return false;
      const exit = kind === 'final' || kind === 'unload';
      if (exit ? !hasPlayed : !canReport(stateName)) return false;
      if ((kind === 'pause' || kind === 'ended') && !hasPlayed) return false;
      const position = Math.max(0, tracker.position);
      if ((kind === 'periodic' || kind === 'seek') && lastReported.current != null && Math.abs(position - lastReported.current) < 1) {
        return false;
      }
      const body: ProgressRequest = {
        item_type: item.type,
        item_id: item.id,
        media_file_id: fileId,
        position_seconds: position,
        duration_seconds: tracker.duration,
      };
      lastReported.current = position;
      const request = putProgress(body, exit ? { keepalive: true } : {});
      if (kind === 'final') {
        void registerFinalReport(queryClient, item, request, latest.current.onFinalPrompt);
        return true;
      }
      request.then(
        (progress) => {
          if (progress?.watched) setMarkedWatched(true);
          if (progress?.rating_prompt) setRatingPrompt(progress.rating_prompt);
        },
        (err: unknown) => {
          if (lastReported.current === position) lastReported.current = null;
          if (isApiError(err)) {
            if (err.status === 404) stopped.current = true;
            else logApiError(err);
          }
        },
      );
      return true;
    },
    [queryClient],
  );

  // The flag is set only when a report was actually sent: under StrictMode the simulated
  // unmount before the first frame must not consume the mount's final report.
  const sendFinal = useCallback(() => {
    if (finalSent.current) return;
    if (report('final')) finalSent.current = true;
  }, [report]);

  // Every 10 s while playing (tracking live).
  const playing = options.stateName === 'playing';
  useEffect(() => {
    if (!playing) return;
    const timer = setInterval(() => {
      if (latest.current.tracker.live) report('periodic');
    }, REPORT_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [playing, report]);

  // pause / seeked (debounced) / ended from the tracker.
  const { tracker } = options;
  useEffect(() => {
    let seekTimer: ReturnType<typeof setTimeout> | null = null;
    const unsubscribe = tracker.subscribe((type) => {
      if (type === 'pause') report('pause');
      else if (type === 'ended') report('ended');
      else if (type === 'seeked') {
        if (seekTimer !== null) clearTimeout(seekTimer);
        seekTimer = setTimeout(() => report('seek'), SEEK_REPORT_DEBOUNCE_MS);
      }
    });
    return () => {
      unsubscribe();
      if (seekTimer !== null) clearTimeout(seekTimer);
    };
  }, [tracker, report]);

  // Unload reports (keepalive; not final: the page may come back).
  useEffect(() => {
    const onPageHide = () => void report('unload');
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') report('unload');
    };
    window.addEventListener('pagehide', onPageHide);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [report]);

  // The final report on unmount (declare this hook BEFORE usePlaybackSession so it runs before
  // the element is released).
  useEffect(() => () => sendFinal(), [sendFinal]);

  return { markedWatched, ratingPrompt, sendFinal };
}
