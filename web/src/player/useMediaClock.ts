import { useEffect, useState } from 'react';

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
  const duration = video.duration;
  return {
    current: Number.isFinite(video.currentTime) ? video.currentTime : 0,
    duration: Number.isFinite(duration) && duration > 0 ? duration : null,
    buffered: readBuffered(video),
  };
}

/** The element's time/duration/buffered ranges for the controls (display only, never reported). */
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
