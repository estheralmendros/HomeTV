import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { mediaState } from '../../tests/media';
import { useMediaClock, useMediaSelector } from './useMediaClock';

const fire = (video: HTMLMediaElement, type: string) => act(() => void video.dispatchEvent(new Event(type)));

describe('useMediaClock', () => {
  it('follows timeupdate / durationchange', () => {
    const video = document.createElement('video');
    const { result } = renderHook(() => useMediaClock(video));
    expect(result.current).toEqual({ current: 0, duration: null, buffered: [] });
    mediaState(video).duration = 1320;
    mediaState(video).currentTime = 12;
    fire(video, 'durationchange');
    expect(result.current).toMatchObject({ current: 12, duration: 1320 });
  });
});

describe('useMediaSelector', () => {
  it('re-renders only when the selected value changes, not on every timeupdate', () => {
    const video = document.createElement('video');
    mediaState(video).duration = 100;
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return useMediaSelector(video, (v) => v.duration - v.currentTime <= 30);
    });
    expect(result.current).toBe(false);
    const initial = renders;
    for (const t of [10, 20, 30, 40, 50, 60]) {
      mediaState(video).currentTime = t;
      fire(video, 'timeupdate');
    }
    expect(renders).toBe(initial);
    mediaState(video).currentTime = 71;
    fire(video, 'timeupdate');
    expect(result.current).toBe(true);
    expect(renders).toBe(initial + 1);
    mediaState(video).currentTime = 80;
    fire(video, 'timeupdate');
    expect(renders).toBe(initial + 1);
  });
});
