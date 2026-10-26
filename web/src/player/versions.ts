// Version choice and start position (prd2 §7 step 3, §9 step 2; prd.md §9 D10/D11/D13). Pure:
// shared by the detail pages (labels, defaults) and the player. `media_files` keep the server
// order (prd.md Rev 8) and are never re-sorted, so "first … in media_files order" picks the
// same file as the TV app.
import type { ClientCaps, MediaFile, Progress } from '@/api/types';
import { formatBytes } from '@/lib/format';
import { S } from '@/strings.es';

export interface StartPosition {
  seconds: number;
  /** Resumable progress exists but this version doesn't match it: the player shows a notice. */
  differentVersion: boolean;
}

/** Positions below this are not worth resuming. */
const MIN_RESUME_SECONDS = 30;
/** A part is finished in its last 30 s… */
const FINISHED_TAIL_SECONDS = 30;
/** …or from 90 % of it. */
const FINISHED_RATIO = 0.9;

const readable = (f: MediaFile) => f.probe_error === null;
const partOf = (f: MediaFile) => f.part_number ?? null;

/** Readable and its video codec is in caps. */
export function isPlayable(f: MediaFile, caps: ClientCaps): boolean {
  return readable(f) && f.video !== null && caps.video_codecs.includes(f.video.codec);
}

/** The last-played file (`progress.media_file_id`) when it is still among `files`. */
export function lastPlayedFile(files: readonly MediaFile[], progress: Progress | null): MediaFile | null {
  if (progress?.media_file_id == null) return null;
  return files.find((f) => f.id === progress.media_file_id) ?? null;
}

/** Last 30 s or ≥ 90 % of the part (the server keeps watched=false until 95 % of the LAST part). */
function isFinished(file: MediaFile, progress: Progress): boolean {
  const duration = file.duration_seconds ?? progress.duration_seconds;
  if (!(duration > 0)) return false;
  const position = progress.position_seconds;
  return position >= duration - FINISHED_TAIL_SECONDS || position >= FINISHED_RATIO * duration;
}

/** Whether nextPart() can be non-null; independent of caps (its last fallback ignores them). */
const NO_CAPS: ClientCaps = {
  containers: [],
  video_codecs: [],
  audio_codecs: [],
  max_audio_channels: 0,
  hls: false,
  can_select_audio_track: false,
};

/** D10: the last-played file is a part, finished, and a next part exists. */
function finishedNonFinalPart(files: readonly MediaFile[], last: MediaFile | null, progress: Progress): boolean {
  return last !== null && last.part_number !== null && isFinished(last, progress) && nextPart(files, last, NO_CAPS) !== null;
}

/**
 * D13: among the files with the smallest part number greater than `file`'s: same label and
 * playable → first playable → same label and readable → first readable. Null for the last part
 * or a standalone file.
 */
export function nextPart(files: readonly MediaFile[], file: MediaFile, caps: ClientCaps): MediaFile | null {
  const current = file.part_number;
  if (current === null) return null;
  const later = files.filter((f) => f.part_number !== null && f.part_number > current);
  if (later.length === 0) return null;
  const next = Math.min(...later.map((f) => f.part_number as number));
  const group = later.filter((f) => f.part_number === next);
  const sameLabel = (f: MediaFile) => f.label === file.label;
  return (
    group.find((f) => sameLabel(f) && isPlayable(f, caps)) ??
    group.find((f) => isPlayable(f, caps)) ??
    group.find((f) => sameLabel(f) && readable(f)) ??
    group.find(readable) ??
    null
  );
}

/**
 * D11: `f` may carry the saved position: both durations known and within max(60 s, 2 %) of
 * `progress.duration_seconds`, and the same part as the last-played file (when it is gone:
 * only items without parts can match).
 */
export function matchesLastPlayed(f: MediaFile, files: readonly MediaFile[], progress: Progress | null): boolean {
  if (!progress || f.duration_seconds === null || !(progress.duration_seconds > 0)) return false;
  const expected = progress.duration_seconds;
  if (Math.abs(f.duration_seconds - expected) > Math.max(60, 0.02 * expected)) return false;
  const last = lastPlayedFile(files, progress);
  if (last) return partOf(f) === partOf(last);
  return !files.some((x) => x.part_number !== null);
}

/** The default version (§7 step 3). Null only when `files` is empty. */
export function selectVersion(files: readonly MediaFile[], caps: ClientCaps, progress: Progress | null): MediaFile | null {
  if (files.length === 0) return null;
  const playable = (f: MediaFile) => isPlayable(f, caps);

  // 1. Resume (D10 + D11); a watched item starts over.
  if (progress && !progress.watched) {
    const last = lastPlayedFile(files, progress);
    if (last && finishedNonFinalPart(files, last, progress)) {
      const next = nextPart(files, last, caps);
      if (next) return next;
    }
    if (last && playable(last)) return last;
    const match = files.find((f) => playable(f) && matchesLastPlayed(f, files, progress));
    if (match) return match;
  }

  // 2. Film with parts: a playable standalone version, else Part 1 (lowest part number).
  const parts = files.filter((f) => f.part_number !== null);
  if (parts.length > 0) {
    const standalone = files.find((f) => f.part_number === null && playable(f));
    if (standalone) return standalone;
    const lowest = Math.min(...parts.map((f) => f.part_number as number));
    const first = parts.filter((f) => f.part_number === lowest);
    const pick = first.find(playable) ?? first.find(readable);
    if (pick) return pick;
  }

  // 3. First playable; 4. first readable; else the first file (the server answers unsupported).
  return files.find(playable) ?? files.find(readable) ?? files[0] ?? null;
}

/** Where `f` starts (§7 step 3 `startPosition`, §9 step 2). */
export function startPosition(f: MediaFile, files: readonly MediaFile[], progress: Progress | null): StartPosition {
  const atZero = { seconds: 0, differentVersion: false };
  if (!progress || progress.watched || progress.position_seconds < MIN_RESUME_SECONDS) return atZero;
  const last = lastPlayedFile(files, progress);
  // A finished non-final part: the next part starts at 0 by design, and so does every file.
  if (finishedNonFinalPart(files, last, progress)) return atZero;
  const position = progress.position_seconds;
  if (last && f.id === last.id) return { seconds: position, differentVersion: false };
  if (matchesLastPlayed(f, files, progress)) {
    const duration = f.duration_seconds as number;
    return position >= duration - FINISHED_TAIL_SECONDS ? atZero : { seconds: position, differentVersion: false };
  }
  return { seconds: 0, differentVersion: true };
}

/** "label · 23,4 GB", or "Parte N · label · 23,4 GB" for a part. */
export function versionLabel(file: MediaFile): string {
  const base = `${file.label} · ${formatBytes(file.size_bytes)}`;
  return file.part_number === null ? base : `${S.versions.part(file.part_number)} · ${base}`;
}

/** VersionPicker option: versionLabel plus "(ilegible)" or the may-not-play warning. */
export function versionOptionLabel(file: MediaFile, caps: ClientCaps): string {
  const label = versionLabel(file);
  if (!readable(file)) return `${label} ${S.versions.unreadable}`;
  if (!isPlayable(file, caps)) return `${label} ${S.versions.mayNotPlay}`;
  return label;
}

/**
 * Other versions to offer on the unsupported/error screen (§9.1): readable, playable per caps,
 * the same part as `current`, and not `excluded` (the mount's failedFileIds).
 */
export function playableAlternatives(
  files: readonly MediaFile[],
  current: MediaFile,
  caps: ClientCaps,
  excluded: ReadonlySet<number> | readonly number[] = [],
): MediaFile[] {
  const isExcluded = (id: number) => ('has' in excluded ? excluded.has(id) : excluded.includes(id));
  return files.filter(
    (f) => f.id !== current.id && isPlayable(f, caps) && partOf(f) === partOf(current) && !isExcluded(f.id),
  );
}
