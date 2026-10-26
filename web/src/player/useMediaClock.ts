import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';

export interface MediaClock {
  current: number;
  /** The element's duration when finite. */
  duration: number | null;
  buffered: Array<[number, number]>;
}

const CLOCK_EVENTS = ['timeupdate', 'durationchange', 'loadedmetadata', 'progress', 'seeked', 'emptied', 'ended'] as const;

function readBuffered(video: HTMLMediaElement): Array<[number, number]> {
  const ranges: Array<[number, number]> = [];
  try {
    const { buffered } = video;
    for (let i = 0; i < buffered.length; i += 1) ranges.push([buffered.start(i), buffered.end(i)]);
  } catch {
    // no buffered ranges available
  }
  return ranges;
}

function read(video: HTMLMediaElement): MediaClock {
  return {
    current: Number.isFinite(video.currentTime) ? video.currentTime : 0,
    duration: mediaDuration(video),
    buffered: readBuffered(video),
  };
}

/**
 * The element's time/duration/buffered ranges for the controls (display only, never reported).
 * Re-renders its caller on every clock event (about 4/s while playing): use it in the controls,
 * and `useMediaSelector` in components that only need a derived value.
 */
export function useMediaClock(video: HTMLMediaElement): MediaClock {
  const [clock, setClock] = useState<MediaClock>(() => read(video));
  useEffect(() => {
    const update = () => setClock(read(video));
    for (const type of CLOCK_EVENTS) video.addEventListener(type, update);
    return () => {
      for (const type of CLOCK_EVENTS) video.removeEventListener(type, update);
    };
  }, [video]);
  return clock;
}

/** The element's duration when finite and > 0. */
export function mediaDuration(video: HTMLMediaElement): number | null {
  const { duration } = video;
  return Number.isFinite(duration) && duration > 0 ? duration : null;
}

/**
 * A value derived from the element on every clock event; the caller re-renders only when it
 * changes (`Object.is`), so `select` must return a primitive.
 */
export function useMediaSelector<T extends string | number | boolean | null>(video: HTMLMediaElement, select: (video: HTMLMediaElement) => T): T {
  const subscribe = useCallback((onChange: () => void) => listen(video, CLOCK_EVENTS, onChange), [video]);
  return useSyncExternalStore(subscribe, () => select(video));
}

const PAUSED_EVENTS = ['play', 'pause', 'playing', 'emptied', 'ended'] as const;

/**
 * `video.paused` as an external store. Never read it raw during render: a render that React
 * bails out of (same state) still keeps its effect deps, so a mutable read there can leave a
 * `[playing]` effect believing it already ran.
 */
export function useMediaPaused(video: HTMLMediaElement): boolean {
  const subscribe = useCallback((onChange: () => void) => listen(video, PAUSED_EVENTS, onChange), [video]);
  return useSyncExternalStore(subscribe, () => video.paused);
}

function listen(video: HTMLMediaElement, events: readonly string[], onChange: () => void): () => void {
  for (const type of events) video.addEventListener(type, onChange);
  return () => {
    for (const type of events) video.removeEventListener(type, onChange);
  };
}
