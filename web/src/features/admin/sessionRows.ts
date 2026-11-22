// Pure display helpers of the Reproducciones tab: one AdminSession -> its Spanish texts.
import type { AdminSession, AudioAction, VideoAction } from '@/api/types';
import { episodeCode, formatDecimal, formatDuration } from '@/lib/format';
import { S } from '@/strings.es';

const T = S.admin.sessions;

/** Film title, "Show · T4 · E1" for an episode, "Sin identificar" without a title. */
export function sessionTitle(s: AdminSession): string {
  if (s.title === null) return T.untitled;
  if (s.item_type === 'episode' && s.season_number !== null && s.episode_number !== null) {
    return `${s.title} · ${episodeCode(s.season_number, s.episode_number, s.last_episode_number ?? undefined)}`;
  }
  return s.title;
}

export const profileLabel = (s: AdminSession): string => s.profile_name ?? T.deletedProfile;

export const videoLabel = (action: VideoAction): string => T.video[action];

export const audioLabel = (action: AudioAction): string => T.audio[action];

export function sessionStateLabel(s: AdminSession): string {
  if (s.state === 'running') return s.video_action === 'h264' ? T.state.converting : T.state.streaming;
  return T.state[s.state];
}

/** "12 · 1:12"; "—" when the segment or the position is unknown. */
export function lastSegmentText(s: AdminSession): string {
  if (s.last_segment === null || s.position_seconds === null) return T.none;
  return `${s.last_segment} · ${formatDuration(s.position_seconds)}`;
}

/** "1,4×"; "—" when unknown. */
export const speedText = (speed: number | null): string => (speed === null ? T.none : T.speed(formatDecimal(speed, 1)));

/** Accessible name of the "Detener" button: the title, else the file name. */
export const stopLabel = (s: AdminSession): string => T.stopLabel(s.title === null ? s.file_name : sessionTitle(s));
