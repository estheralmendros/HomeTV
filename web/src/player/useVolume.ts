// Volume and mute, persisted to `hometv.volume` = { volume: 0..1, muted }.
// They are user preferences: kept on the shell's <video> across PlayerPage mounts.
import { useCallback, useEffect, useState } from 'react';
import { readJson, writeJson } from '@/lib/storage';

export const VOLUME_KEY = 'hometv.volume';

export interface VolumePrefs {
  volume: number;
  muted: boolean;
}

const clamp = (v: number) => Math.min(1, Math.max(0, v));

export function readVolumePrefs(): VolumePrefs {
  const raw = readJson(VOLUME_KEY) as Partial<VolumePrefs> | null;
  const volume = typeof raw?.volume === 'number' && Number.isFinite(raw.volume) ? clamp(raw.volume) : 1;
  return { volume, muted: raw?.muted === true };
}

export interface VolumeControls extends VolumePrefs {
  setVolume: (volume: number) => void;
  changeVolume: (delta: number) => void;
  toggleMute: () => void;
}

export function useVolume(video: HTMLMediaElement): VolumeControls {
  const [prefs, setPrefs] = useState<VolumePrefs>(readVolumePrefs);

  // Apply the stored preference once per mount (volume is read-only on iOS: harmless there).
  useEffect(() => {
    const stored = readVolumePrefs();
    try {
      video.volume = stored.volume;
    } catch {
      // read-only volume
    }
    video.muted = stored.muted;
  }, [video]);

  const apply = useCallback(
    (next: VolumePrefs) => {
      try {
        video.volume = next.volume;
      } catch {
        // read-only volume
      }
      video.muted = next.muted;
      writeJson(VOLUME_KEY, next);
      setPrefs(next);
    },
    [video],
  );

  const setVolume = useCallback((volume: number) => apply({ volume: clamp(volume), muted: volume <= 0 }), [apply]);
  const changeVolume = useCallback(
    (delta: number) => {
      const current = readVolumePrefs();
      const volume = clamp(Math.round((current.volume + delta) * 100) / 100);
      apply({ volume, muted: volume <= 0 ? true : false });
    },
    [apply],
  );
  const toggleMute = useCallback(() => {
    const current = readVolumePrefs();
    apply({ ...current, muted: !video.muted });
  }, [apply, video]);

  return { ...prefs, setVolume, changeVolume, toggleMute };
}
