// What follows the current playback:
// - next episode (episodes only): resolved with resolveNextEpisode from the FETCHED
//   `['episode', id]` detail, once the last 30 s are reached or on `ended`; the prompt shows in
//   the last 30 s / on end, and `ended` starts a 10 s countdown that auto-advances. Cancel hides
//   it until the next mount.
// - next part (films whose file has a part_number and nextPart() is not null): prompt on `ended`
//   only, no countdown.
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { EpisodeDetail, MediaFile } from '@/api/types';
import { getClientCaps } from './capabilities';
import { resolveNextEpisode } from './nextEpisode';
import type { PlayerStateName } from './playerMachine';
import { nextPart } from './versions';

export const NEXT_EPISODE_LEAD_SECONDS = 30;
export const NEXT_EPISODE_COUNTDOWN_SECONDS = 10;

const PLAYBACK_STATES: ReadonlySet<PlayerStateName> = new Set(['playing', 'paused', 'buffering', 'ended']);

/** Whether `current` is in the last NEXT_EPISODE_LEAD_SECONDS of `duration`. */
export function isWithinLead(current: number, duration: number | null): boolean {
  return duration != null && duration - current <= NEXT_EPISODE_LEAD_SECONDS;
}

export interface NextUpInput {
  /** The fetched episode detail (never the home entry); undefined for films or while loading. */
  episode: EpisodeDetail | undefined;
  isEpisode: boolean;
  files: readonly MediaFile[];
  currentFile: MediaFile | null;
  stateName: PlayerStateName;
  hasPlayed: boolean;
  /** The element is within NEXT_EPISODE_LEAD_SECONDS of the duration (see `isWithinLead`). */
  withinLead: boolean;
  /** Starts the next episode (final report, NEXT, replace navigation). */
  onNextEpisode: (next: EpisodeDetail) => void;
}

export function useNextUp({
  episode,
  isEpisode,
  files,
  currentFile,
  stateName,
  hasPlayed,
  withinLead,
  onNextEpisode,
}: NextUpInput) {
  const queryClient = useQueryClient();
  const ended = stateName === 'ended';
  const nearEnd = hasPlayed && PLAYBACK_STATES.has(stateName) && withinLead;
  const reached = ended || nearEnd;

  // --- next episode -----------------------------------------------------------------------
  const [triggered, setTriggered] = useState(false);
  const [resolution, setResolution] = useState<{ done: boolean; next: EpisodeDetail | null }>({ done: false, next: null });
  const [cancelled, setCancelled] = useState(false);

  useEffect(() => {
    if (isEpisode && reached) setTriggered(true);
  }, [isEpisode, reached]);

  const playedFileId = currentFile?.id ?? null;
  const resolvable = triggered && episode !== undefined && playedFileId !== null;
  useEffect(() => {
    if (!resolvable || !episode || playedFileId === null) return;
    const controller = new AbortController();
    void resolveNextEpisode(queryClient, episode, playedFileId, { signal: controller.signal }).then((next) => {
      if (!controller.signal.aborted) setResolution({ done: true, next });
    });
    return () => controller.abort();
    // Resolved once per file: later refetches of the detail (new object) don't restart it.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: `episode`/`queryClient` are not triggers
  }, [resolvable, playedFileId]);

  const nextEpisode = resolution.next;
  const episodePromptVisible = isEpisode && nextEpisode !== null && reached && !cancelled;

  // Countdown after `ended`.
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);
  const counting = episodePromptVisible && ended;
  const advance = useRef(onNextEpisode);
  advance.current = onNextEpisode;
  useEffect(() => {
    if (!counting || !nextEpisode) {
      setSecondsLeft(null);
      return;
    }
    let left = NEXT_EPISODE_COUNTDOWN_SECONDS;
    setSecondsLeft(left);
    const timer = setInterval(() => {
      left -= 1;
      if (left <= 0) {
        clearInterval(timer);
        advance.current(nextEpisode);
      } else {
        setSecondsLeft(left);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [counting, nextEpisode]);

  // --- next part ----------------------------------------------------------------------------
  const nextPartFile = !isEpisode && currentFile?.part_number != null ? nextPart(files, currentFile, getClientCaps()) : null;
  const partPromptVisible = ended && nextPartFile !== null;

  return {
    nextEpisode: episodePromptVisible ? nextEpisode : null,
    secondsLeft: episodePromptVisible ? secondsLeft : null,
    cancelNextEpisode: () => setCancelled(true),
    /** No next-episode prompt will follow: a film, no next episode, or the prompt was cancelled. */
    noNextEpisode: !isEpisode || cancelled || (resolution.done && resolution.next === null),
    nextPartFile: partPromptVisible ? nextPartFile : null,
  };
}
