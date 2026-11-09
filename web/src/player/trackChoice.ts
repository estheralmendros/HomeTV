// The remembered track choice's matching rule (prd.md FR-3..FR-5a), pure. Web signatures drop
// `visible`/`mode`: the web shows text tracks only, so matchSubtitle filters them itself (FR-5).
// Executed against shared/track-choice-cases.json by trackChoice.test.ts.
import type { AudioChoice, AudioTrack, SubtitleChoice, SubtitleTrack, TrackChoice } from '@/api/types';
import { toBcp47 } from '@/lib/languages';
import { defaultSubtitle, textTracks } from './tracks';

/** The server's limit for a stored title (FR-9); longer titles are cut. */
const MAX_TITLE = 1000;

/** FR-3: empty → null; region/script dropped; 'und' stays 'und'; else the BCP 47 code or the code. */
export function languageKey(code: string | null | undefined): string | null {
  const lower = (code ?? '').trim().toLowerCase();
  if (lower === '') return null;
  const primary = lower.split(/[-_]/)[0] ?? '';
  if (primary === '') return null;
  if (primary === 'und') return 'und';
  return toBcp47(primary) ?? primary;
}

export function sameLanguage(a: string | null | undefined, b: string | null | undefined): boolean {
  const ka = languageKey(a);
  return ka !== null && ka === languageKey(b);
}

const normalTitle = (title: string | null | undefined) => title?.trim() || null;
const sameTitle = (a: string | null | undefined, b: string | null | undefined) => normalTitle(a) === normalTitle(b);

/** The file default: the `default`-flagged audio track, else the first. */
export function defaultAudio(tracks: readonly AudioTrack[]): AudioTrack | null {
  return tracks.find((t) => t.default) ?? tracks[0] ?? null;
}

/** FR-4: the index to send as `audio_stream_index` (null = the file default). */
export function matchAudio(choice: AudioChoice | null, tracks: readonly AudioTrack[]): number | null {
  if (!choice || tracks.length === 0) return null;
  const candidates = tracks.filter((t) => sameLanguage(t.language, choice.language));
  if (candidates.length === 0) return null;
  const fileDefault = defaultAudio(tracks);
  const chosen =
    candidates.find((t) => sameTitle(t.title, choice.title) && t.channels === choice.channels) ??
    candidates.find((t) => t === fileDefault) ??
    candidates[0];
  return !chosen || chosen === fileDefault ? null : chosen.index;
}

const sameFlags = (t: SubtitleTrack, c: SubtitleChoice) => t.forced === c.forced && t.hearing_impaired === c.hearing_impaired;

/** FR-5 (web): the subtitle id to select at a file's first decision (null = Off). */
export function matchSubtitle(choice: SubtitleChoice | null, tracks: readonly SubtitleTrack[], audioLanguage: string | null): string | null {
  const fallback = () => defaultSubtitle(tracks, { audioLanguage })?.id ?? null;
  if (!choice) return fallback();
  if (choice.mode === 'off') return null;
  const candidates = textTracks(tracks).filter((t) => sameLanguage(t.language, choice.language));
  if (candidates.length === 0) return fallback();
  const exact = candidates.filter((t) => t.kind === choice.kind && sameFlags(t, choice) && t.source === choice.source);
  const exactPick = exact.find((t) => sameTitle(t.title, choice.title)) ?? exact[0];
  if (exactPick) return exactPick.id;
  const sameKind = candidates.filter((t) => t.kind === choice.kind);
  const pool =
    sameKind.length > 0 ? sameKind : [...candidates.filter((t) => t.kind === 'text'), ...candidates.filter((t) => t.kind !== 'text')];
  const pick =
    pool.find((t) => sameFlags(t, choice)) ??
    pool.find((t) => t.forced === choice.forced) ??
    pool.find((t) => !t.forced && !t.hearing_impaired) ??
    pool[0];
  return pick?.id ?? null;
}

/** Both parts for the case table; the subtitle default uses the matched (else default) audio's language. */
export function matchTrackChoice(
  choice: TrackChoice | null,
  audio: readonly AudioTrack[],
  subtitles: readonly SubtitleTrack[],
): { audioStreamIndex: number | null; subtitleId: string | null } {
  const audioStreamIndex = matchAudio(choice?.audio ?? null, audio);
  const audioTrack = audio.find((t) => t.index === audioStreamIndex) ?? defaultAudio(audio);
  return { audioStreamIndex, subtitleId: matchSubtitle(choice?.subtitles ?? null, subtitles, audioTrack?.language ?? null) };
}

const pickLanguage = (language: string | null | undefined) => language?.trim() || 'und';
const pickTitle = (title: string | null) => (title === null ? null : title.slice(0, MAX_TITLE));

/** FR-5a: blank language → 'und'; channels outside 1..32 → null (the server would answer 422). */
export function audioChoiceOf(track: AudioTrack): AudioChoice {
  const { channels } = track;
  return {
    language: pickLanguage(track.language),
    title: pickTitle(track.title),
    channels: channels !== null && Number.isInteger(channels) && channels >= 1 && channels <= 32 ? channels : null,
  };
}

/** FR-5a: null (Desactivados) → mode off; a track → its traits. */
export function subtitleChoiceOf(track: SubtitleTrack | null): SubtitleChoice {
  if (!track) return { mode: 'off', language: null, title: null, forced: false, hearing_impaired: false, source: null, kind: null };
  return {
    mode: 'track',
    language: pickLanguage(track.language),
    title: pickTitle(track.title),
    forced: track.forced,
    hearing_impaired: track.hearing_impaired,
    source: track.source,
    kind: track.kind,
  };
}

export function withAudio(choice: TrackChoice | null, audio: AudioChoice): TrackChoice {
  return { audio, subtitles: choice?.subtitles ?? null };
}

export function withSubtitles(choice: TrackChoice | null, subtitles: SubtitleChoice): TrackChoice {
  return { audio: choice?.audio ?? null, subtitles };
}

/** FR-5a (re-pick rule): both non-null and language, title and channels equal (null = null). */
export function sameAudioChoice(a: AudioChoice | null | undefined, b: AudioChoice | null | undefined): boolean {
  return a != null && b != null && a.language === b.language && a.title === b.title && a.channels === b.channels;
}
