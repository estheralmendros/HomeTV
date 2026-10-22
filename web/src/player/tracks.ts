// Subtitle/audio track helpers (prd2 §9 steps 6–8): visible tracks, default selection, labels.
import type { AudioTrack, SubtitleTrack } from '@/api/types';
import { languageName } from '@/lib/languages';
import { readString, writeString } from '@/lib/storage';
import { S } from '@/strings.es';

export const SUBTITLES_OFF = 'off';

/**
 * Line (counted from the bottom) for cues the VTT leaves at `line: auto`. The text-track area is
 * the whole <video>, which sits under the control bar, so bottom-line cues would be hidden by it.
 */
export const SUBTITLE_CUE_LINE = -3;

interface PositionedCue {
  line: number | 'auto' | LineAndPositionSetting;
  snapToLines: boolean;
}

/** Moves auto-positioned cues up to SUBTITLE_CUE_LINE; cues the file positions are kept. */
export function liftCues(cues: ArrayLike<PositionedCue> | null | undefined): void {
  if (!cues) return;
  for (let i = 0; i < cues.length; i += 1) {
    const cue = cues[i];
    if (cue && cue.line === 'auto' && cue.snapToLines) cue.line = SUBTITLE_CUE_LINE;
  }
}

/** Only text tracks are shown; image tracks (PGS/VobSub) never. */
export const textTracks = (tracks: readonly SubtitleTrack[]) => tracks.filter((t) => t.kind === 'text');

export const hasImageTracks = (tracks: readonly SubtitleTrack[]) => tracks.some((t) => t.kind === 'image');

export interface SubtitleDefaults {
  /** `hometv.subtitleLang.<pid>`: an ISO 639-2 code, "off" or null. */
  storedLang: string | null;
  /** Language of the selected audio track. */
  audioLanguage: string | null;
}

/** Stored language → `default` flag → forced track in the audio language → none. */
export function defaultSubtitle(tracks: readonly SubtitleTrack[], { storedLang, audioLanguage }: SubtitleDefaults): SubtitleTrack | null {
  const text = textTracks(tracks);
  if (storedLang === SUBTITLES_OFF) return null;
  if (storedLang) {
    const stored = text.find((t) => t.language === storedLang);
    if (stored) return stored;
  }
  const flagged = text.find((t) => t.default);
  if (flagged) return flagged;
  if (audioLanguage) {
    const forced = text.find((t) => t.forced && t.language === audioLanguage);
    if (forced) return forced;
  }
  return null;
}

/** "Inglés · Commentary · Forzados · SDH · Externo". */
export function subtitleLabel(track: SubtitleTrack): string {
  const parts = [languageName(track.language)];
  if (track.title) parts.push(track.title);
  if (track.forced) parts.push(S.player.subtitles.forced);
  if (track.hearing_impaired) parts.push(S.player.subtitles.sdh);
  if (track.source === 'external') parts.push(S.player.subtitles.external);
  return parts.join(' · ');
}

/** 2 → "2.0", 6 → "5.1", 8 → "7.1". */
function channelsLabel(channels: number | null): string | null {
  if (channels == null || channels < 1) return null;
  return channels <= 2 ? `${channels}.0` : `${channels - 1}.1`;
}

/** "Inglés · EAC3 5.1 · Surround". */
export function audioLabel(track: AudioTrack): string {
  const format = [track.codec.toUpperCase(), channelsLabel(track.channels)].filter(Boolean).join(' ');
  return [languageName(track.language), format, track.title].filter(Boolean).join(' · ');
}

const subtitleLangKey = (profileId: number) => `hometv.subtitleLang.${profileId}`;

export const readSubtitleLang = (profileId: number) => readString(subtitleLangKey(profileId));

export const writeSubtitleLang = (profileId: number, value: string) => writeString(subtitleLangKey(profileId), value);
