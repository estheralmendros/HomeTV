// Media Session wiring (prd2 §9 step 12, /t-fix headset / Bluetooth play-pause): a headset's
// multifunction button, OS media keys and the Now Playing widget reach the player through
// explicit action handlers instead of each browser's default (unreliable with MSE/hls.js and on
// iPadOS). Feature-detected; a no-op without `navigator.mediaSession`. Effects only: the handlers
// call the same session/seek callbacks as the Play/Pause and ∓10 s controls.
import { useEffect, useRef } from 'react';
import { artworkUrl } from '@/api/urls';
import type { EpisodeDetail, FilmDetail, ItemType } from '@/api/types';
import { episodeLine } from '@/lib/episodes';
import { SEEK_STEP_SECONDS } from './useKeyboardShortcuts';
import type { DecisionSource } from './useStartDecision';

export interface MediaSessionInfo {
  title: string;
  artist?: string;
  artworkSrc?: string;
}

export interface MediaSessionOptions {
  /** null until the item (or the home fallback entry) is known. */
  info: MediaSessionInfo | null;
  /** The controls show Pausa. */
  playing: boolean;
  /** False where keyboard shortcuts are disabled (preparing, error screens): handlers do nothing. */
  enabled: boolean;
  onPlay: () => void;
  onPause: () => void;
  onSeekBy: (deltaSeconds: number) => void;
}

const ACTIONS = ['play', 'pause', 'seekbackward', 'seekforward'] as const satisfies readonly MediaSessionAction[];

function mediaSession(): MediaSession | null {
  return typeof navigator !== 'undefined' && 'mediaSession' in navigator ? navigator.mediaSession : null;
}

/**
 * Now Playing info: a film's player title (`Dune (2021)`, `… · Parte 2`) + poster; an episode's
 * "T2 · E3 · Título" by the show + still (else the show poster).
 */
export function mediaSessionInfo(type: ItemType, source: DecisionSource | undefined, playerTitle: string): MediaSessionInfo | null {
  if (!source) return null;
  if (type === 'episode') {
    const ep = source as EpisodeDetail;
    return { title: episodeLine(ep), artist: ep.show_title, artworkSrc: artworkUrl(ep.still ?? ep.show_poster, 'large') ?? undefined };
  }
  return { title: playerTitle, artworkSrc: artworkUrl((source as FilmDetail).poster, 'large') ?? undefined };
}

export function useMediaSession(options: MediaSessionOptions): void {
  const latest = useRef(options);
  latest.current = options;

  // Handlers: registered once per mount, cleared on unmount (the next playback registers its own).
  useEffect(() => {
    const session = mediaSession();
    if (!session) return;
    const run = (fn: (o: MediaSessionOptions) => void) => () => {
      if (latest.current.enabled) fn(latest.current);
    };
    const handlers: Record<(typeof ACTIONS)[number], () => void> = {
      play: run((o) => o.onPlay()),
      pause: run((o) => o.onPause()),
      seekbackward: run((o) => o.onSeekBy(-SEEK_STEP_SECONDS)),
      seekforward: run((o) => o.onSeekBy(SEEK_STEP_SECONDS)),
    };
    const set = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
      try {
        session.setActionHandler(action, handler);
      } catch {
        // an action this browser does not support
      }
    };
    for (const action of ACTIONS) set(action, handlers[action]);
    return () => {
      for (const action of ACTIONS) set(action, null);
      session.metadata = null;
      session.playbackState = 'none';
    };
  }, []);

  const { title, artist, artworkSrc } = options.info ?? {};
  useEffect(() => {
    const session = mediaSession();
    if (!session) return;
    // The item became unknown while mounted (e.g. a 404 after a home-fallback start): no stale
    // Now Playing card over the "gone" screen.
    if (title === undefined || typeof MediaMetadata === 'undefined') {
      session.metadata = null;
      return;
    }
    session.metadata = new MediaMetadata({ title, artist, artwork: artworkSrc ? [{ src: artworkSrc }] : [] });
  }, [title, artist, artworkSrc]);

  const { playing } = options;
  useEffect(() => {
    const session = mediaSession();
    if (session) session.playbackState = playing ? 'playing' : 'paused';
  }, [playing]);
}
