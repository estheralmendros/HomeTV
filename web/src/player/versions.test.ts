import { describe, expect, it } from 'vitest';
import type { MediaFile, Progress } from '@/api/types';
import * as f from '../../tests/fixtures';
import {
  isPlayable,
  matchesLastPlayed,
  nextPart,
  playableAlternatives,
  selectVersion,
  startPosition,
  versionLabel,
  versionName,
  versionOptionLabel,
} from './versions';

const caps = f.clientCaps(); // Chrome: h264/vp9/av1, no HEVC
const hevcCaps = f.clientCaps({ video_codecs: ['h264', 'hevc'] });

const H264 = f.video();
const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160, hdr: 'hdr10' });

function file(id: number, overrides: Partial<MediaFile> = {}): MediaFile {
  return f.mediaFile({ id, subtitle_tracks: [], video: H264, duration_seconds: 7200, ...overrides });
}
const hevc = (id: number, overrides: Partial<MediaFile> = {}) => file(id, { video: HEVC, label: '2160p HEVC HDR10', ...overrides });
const part = (id: number, n: number, overrides: Partial<MediaFile> = {}) =>
  file(id, { part_number: n, duration_seconds: 3000, ...overrides });

function progress(overrides: Partial<Progress>): Progress {
  return f.progress({ position_seconds: 1000, duration_seconds: 7200, watched: false, media_file_id: null, ...overrides });
}

const ids = (files: MediaFile[]) => files.map((x) => x.id);

describe('isPlayable', () => {
  it('needs no probe_error, a video stream and its codec in caps', () => {
    expect(isPlayable(file(1), caps)).toBe(true);
    expect(isPlayable(hevc(1), caps)).toBe(false);
    expect(isPlayable(hevc(1), hevcCaps)).toBe(true);
    expect(isPlayable(file(1, { probe_error: 'ffprobe failed' }), caps)).toBe(false);
    expect(isPlayable(file(1, { video: null }), caps)).toBe(false);
  });
});

describe('selectVersion: single versions and plain siblings', () => {
  it('returns null without files', () => {
    expect(selectVersion([], caps, null)).toBeNull();
  });

  it('prefers progress.media_file_id when it is readable and in caps', () => {
    const files = [file(1), file(2)];
    expect(selectVersion(files, caps, progress({ media_file_id: 2 }))?.id).toBe(2);
  });

  it('skips a first-listed HEVC file without HEVC caps (and keeps it with them)', () => {
    const files = [hevc(1), file(2)];
    expect(selectVersion(files, caps, null)?.id).toBe(2);
    expect(selectVersion(files, hevcCaps, null)?.id).toBe(1);
  });

  it('skips a probe_error file and a file without video', () => {
    expect(selectVersion([file(1, { probe_error: 'x' }), file(2)], caps, null)?.id).toBe(2);
    expect(selectVersion([file(1, { video: null }), file(2)], caps, null)?.id).toBe(2);
  });

  it('falls back to the first readable file, then to media_files[0]', () => {
    expect(selectVersion([file(1, { probe_error: 'x' }), hevc(2), hevc(3)], caps, null)?.id).toBe(2);
    expect(selectVersion([file(1, { probe_error: 'x' }), file(2, { probe_error: 'y' })], caps, null)?.id).toBe(1);
  });

  it('ignores progress when the item is watched', () => {
    const files = [file(1), file(2)];
    expect(selectVersion(files, caps, progress({ media_file_id: 2, watched: true }))?.id).toBe(1);
  });
});

describe('selectVersion: multi-part films', () => {
  const p1 = part(1, 1);
  const p2 = part(2, 2, { duration_seconds: 3200 });

  it('defaults to Part 1', () => {
    expect(selectVersion([p1, p2], caps, null)?.id).toBe(1);
  });

  it('starts Part 2 after Part 1 was finished (last 30 s or ≥ 90 %)', () => {
    expect(selectVersion([p1, p2], caps, progress({ media_file_id: 1, position_seconds: 2975, duration_seconds: 3000 }))?.id).toBe(2);
    expect(selectVersion([p1, p2], caps, progress({ media_file_id: 1, position_seconds: 2700, duration_seconds: 3000 }))?.id).toBe(2);
    expect(selectVersion([p1, p2], caps, progress({ media_file_id: 1, position_seconds: 2690, duration_seconds: 3000 }))?.id).toBe(1);
  });

  it('resumes Part 1 at 50 % and resumes within Part 2', () => {
    expect(selectVersion([p1, p2], caps, progress({ media_file_id: 1, position_seconds: 1500, duration_seconds: 3000 }))?.id).toBe(1);
    expect(selectVersion([p1, p2], caps, progress({ media_file_id: 2, position_seconds: 1000, duration_seconds: 3200 }))?.id).toBe(2);
  });

  it('starts a watched film at Part 1, or at a playable standalone version', () => {
    const watched = progress({ media_file_id: 2, position_seconds: 3200, duration_seconds: 3200, watched: true });
    expect(selectVersion([p1, p2], caps, watched)?.id).toBe(1);
    const standalone = file(9, { duration_seconds: 6200 });
    expect(selectVersion([standalone, p1, p2], caps, watched)?.id).toBe(9);
  });

  it('prefers a playable standalone version over the parts; a standalone not in caps → Part 1', () => {
    expect(selectVersion([file(9), p1, p2], caps, null)?.id).toBe(9);
    expect(selectVersion([hevc(9), p1, p2], caps, null)?.id).toBe(1);
  });

  it('picks another Part 1 version when the first Part 1 is not in caps', () => {
    expect(selectVersion([part(1, 1, { video: HEVC }), part(2, 1), part(3, 2)], caps, null)?.id).toBe(2);
  });

  it('falls back to the first readable Part 1 when no Part 1 is playable', () => {
    expect(selectVersion([part(1, 1, { probe_error: 'x' }), part(2, 1, { video: HEVC }), part(3, 2, { video: HEVC })], caps, null)?.id).toBe(2);
  });

  it('resumes a finished LAST part (no next part)', () => {
    const last = progress({ media_file_id: 2, position_seconds: 3190, duration_seconds: 3200 });
    expect(selectVersion([p1, p2], caps, last)?.id).toBe(2);
    expect(startPosition(p2, [p1, p2], last)).toEqual({ seconds: 3190, differentVersion: false });
  });
});

describe('nextPart', () => {
  it("prefers the next part's playable file with the same label over an earlier-listed one", () => {
    const p1 = part(1, 1, { label: 'A' });
    const files = [p1, part(2, 2, { label: 'B' }), part(3, 2, { label: 'A' })];
    expect(nextPart(files, p1, caps)?.id).toBe(3);
  });

  it('a same-label file not in caps loses to a playable one with another label', () => {
    const p1 = part(1, 1, { video: HEVC, label: '2160p HEVC' });
    const files = [p1, part(2, 2, { video: HEVC, label: '2160p HEVC' }), part(3, 2, { label: '1080p H264' })];
    expect(nextPart(files, p1, caps)?.id).toBe(3);
  });

  it('with nothing playable: the same-label readable file, then the first readable one', () => {
    const p1 = part(1, 1, { video: HEVC, label: 'X' });
    expect(nextPart([p1, part(2, 2, { video: HEVC, label: 'Y' }), part(3, 2, { video: HEVC, label: 'X' })], p1, caps)?.id).toBe(3);
    expect(nextPart([p1, part(2, 2, { probe_error: 'e', label: 'X' }), part(3, 2, { video: HEVC, label: 'Y' })], p1, caps)?.id).toBe(3);
  });

  it('only looks at the smallest greater part number', () => {
    const p1 = part(1, 1);
    expect(nextPart([p1, part(3, 3), part(2, 2, { video: HEVC })], p1, caps)?.id).toBe(2);
  });

  it('is null for the last part, for a standalone file and when every next-part file is unreadable', () => {
    const p1 = part(1, 1);
    const p2 = part(2, 2);
    expect(nextPart([p1, p2], p2, caps)).toBeNull();
    const standalone = file(9);
    expect(nextPart([standalone, p1, p2], standalone, caps)).toBeNull();
    expect(nextPart([p1, part(2, 2, { probe_error: 'x' })], p1, caps)).toBeNull();
  });
});

describe('versionName', () => {
  it('is the file name without its extension', () => {
    expect(versionName(file(1, { file_name: 'Dune (2021) 4K.mkv' }))).toBe('Dune (2021) 4K');
    expect(versionName(file(1, { file_name: 'Dune.Part.Two.2024.2160p.HEVC.mp4' }))).toBe('Dune.Part.Two.2024.2160p.HEVC');
    expect(versionName(file(1, { file_name: 'Dune.tar.mkv' }))).toBe('Dune.tar');
  });

  it('keeps a name with no extension, or whose only dot leads it, as is', () => {
    expect(versionName(file(1, { file_name: 'Dune' }))).toBe('Dune');
    expect(versionName(file(1, { file_name: '.mkv' }))).toBe('.mkv');
    expect(versionName(file(1, { file_name: 'Dune.' }))).toBe('Dune.');
  });

  it('falls back to "Desconocido" for a blank name', () => {
    expect(versionName(file(1, { file_name: '' }))).toBe('Desconocido');
    expect(versionName(file(1, { file_name: '  ' }))).toBe('Desconocido');
  });
});

describe('versionLabel / versionOptionLabel', () => {
  it('reads the file name without extension, with a "Parte N" prefix for parts only', () => {
    expect(versionLabel(file(1, { file_name: 'Dune (2021).mkv', label: '1080p H264 · AAC 2.0' }))).toBe('Dune (2021)');
    expect(versionLabel(part(2, 2, { file_name: 'Dune.cd2.avi', label: '1080p H264 · AAC 2.0' }))).toBe('Parte 2 · Dune.cd2');
  });

  it('adds "(ilegible)" and the browser warning in the picker', () => {
    expect(versionOptionLabel(file(1, { file_name: 'broken.mkv', label: 'Desconocido', probe_error: 'x' }), caps)).toBe('broken (ilegible)');
    expect(versionOptionLabel(hevc(2, { file_name: 'Dune 4K.mkv' }), caps)).toBe('Dune 4K (puede que no se reproduzca en este navegador)');
    expect(versionOptionLabel(file(3, { file_name: 'audio.mka', video: null, label: 'Solo audio' }), caps)).toBe(
      'audio (puede que no se reproduzca en este navegador)',
    );
    expect(versionOptionLabel(file(4, { file_name: 'Dune 1080p.mp4', label: '1080p H264' }), caps)).toBe('Dune 1080p');
  });
});

describe('playableAlternatives', () => {
  const current = part(1, 1);
  const files = [current, part(2, 1), part(3, 1, { video: HEVC }), part(4, 1, { probe_error: 'x' }), part(5, 2)];

  it('lists other playable files of the same part only', () => {
    expect(ids(playableAlternatives(files, current, caps))).toEqual([2]);
    expect(ids(playableAlternatives(files, current, hevcCaps))).toEqual([2, 3]);
  });

  it('drops excluded ids (array or set)', () => {
    expect(playableAlternatives(files, current, caps, [2])).toEqual([]);
    expect(ids(playableAlternatives(files, current, hevcCaps, new Set([2])))).toEqual([3]);
  });

  it('treats two standalone files as the same part', () => {
    expect(ids(playableAlternatives([file(1), file(2), part(3, 1)], file(1), caps))).toEqual([2]);
  });
});

describe('version match: selectVersion + startPosition + matchesLastPlayed', () => {
  it('a matching sibling resumes when the last-played version is not in caps', () => {
    const files = [hevc(1, { duration_seconds: 7200 }), file(2, { duration_seconds: 7190 })];
    const p = progress({ media_file_id: 1, position_seconds: 3000, duration_seconds: 7200 });
    const chosen = selectVersion(files, caps, p);
    expect(chosen?.id).toBe(2);
    expect(startPosition(chosen!, files, p)).toEqual({ seconds: 3000, differentVersion: false });
  });

  it('uses a max(60 s, 2 %) duration tolerance', () => {
    const last = hevc(1, { duration_seconds: 7200 });
    const p = progress({ media_file_id: 1, duration_seconds: 7200 });
    expect(matchesLastPlayed(file(2, { duration_seconds: 7344 }), [last], p)).toBe(true);
    expect(matchesLastPlayed(file(2, { duration_seconds: 7056 }), [last], p)).toBe(true);
    expect(matchesLastPlayed(file(2, { duration_seconds: 7345 }), [last], p)).toBe(false);
    const shortLast = hevc(1, { duration_seconds: 1300 });
    const shortP = progress({ media_file_id: 1, duration_seconds: 1300 });
    expect(matchesLastPlayed(file(2, { duration_seconds: 1360 }), [shortLast], shortP)).toBe(true);
    expect(matchesLastPlayed(file(2, { duration_seconds: 1361 }), [shortLast], shortP)).toBe(false);
  });

  it('a mismatched duration starts the default version at 0 with the notice', () => {
    const files = [hevc(1, { duration_seconds: 7200 }), file(2, { duration_seconds: 6600 })];
    const p = progress({ media_file_id: 1, position_seconds: 3000, duration_seconds: 7200 });
    const chosen = selectVersion(files, caps, p);
    expect(chosen?.id).toBe(2);
    expect(startPosition(chosen!, files, p)).toEqual({ seconds: 0, differentVersion: true });
  });

  it('a sibling with an unknown duration never matches', () => {
    const files = [hevc(1), file(2, { duration_seconds: null })];
    const p = progress({ media_file_id: 1, position_seconds: 3000 });
    expect(matchesLastPlayed(files[1]!, files, p)).toBe(false);
    expect(startPosition(files[1]!, files, p)).toEqual({ seconds: 0, differentVersion: true });
  });

  it('a different part starts at 0 (part mismatch)', () => {
    const files = [
      file(10, { duration_seconds: 3200 }),
      part(11, 1, { duration_seconds: 3100 }),
      part(12, 2, { video: HEVC, duration_seconds: 3200 }),
    ];
    const p = progress({ media_file_id: 12, position_seconds: 1000, duration_seconds: 3200 });
    const chosen = selectVersion(files, caps, p);
    expect(chosen?.id).toBe(10);
    expect(startPosition(chosen!, files, p)).toEqual({ seconds: 0, differentVersion: true });
    expect(startPosition(files[1]!, files, p)).toEqual({ seconds: 0, differentVersion: true });
  });

  it('a removed last-played file uses the duration test alone (items without parts)', () => {
    const files = [file(501, { duration_seconds: 1320 })];
    const near = progress({ media_file_id: null, position_seconds: 600, duration_seconds: 1300 });
    expect(selectVersion(files, caps, near)?.id).toBe(501);
    expect(startPosition(files[0]!, files, near)).toEqual({ seconds: 600, differentVersion: false });
    const unlinked = { ...near, media_file_id: 999 };
    expect(startPosition(files[0]!, files, unlinked)).toEqual({ seconds: 600, differentVersion: false });
    const far = progress({ media_file_id: null, position_seconds: 600, duration_seconds: 1250 });
    expect(startPosition(files[0]!, files, far)).toEqual({ seconds: 0, differentVersion: true });
  });

  it('a film with parts and no last-played file uses the default rules at 0', () => {
    const files = [part(1, 1), part(2, 2)];
    const p = progress({ media_file_id: null, position_seconds: 600, duration_seconds: 3000 });
    const chosen = selectVersion(files, caps, p);
    expect(chosen?.id).toBe(1);
    expect(startPosition(chosen!, files, p).seconds).toBe(0);
  });

  it('the next part takes precedence over a matching Part 1 sibling', () => {
    const files = [part(1, 1, { video: HEVC }), part(2, 1), part(3, 2)];
    const p = progress({ media_file_id: 1, position_seconds: 2980, duration_seconds: 3000 });
    const chosen = selectVersion(files, caps, p);
    expect(chosen?.id).toBe(3);
    for (const x of files) expect(startPosition(x, files, p)).toEqual({ seconds: 0, differentVersion: false });
  });

  it('with two Part 2 versions the playable one wins over the same label', () => {
    const files = [
      part(1, 1, { video: HEVC, label: '2160p HEVC' }),
      part(2, 2, { video: HEVC, label: '2160p HEVC' }),
      part(3, 2, { label: '1080p H264' }),
    ];
    const p = progress({ media_file_id: 1, position_seconds: 2990, duration_seconds: 3000 });
    expect(selectVersion(files, caps, p)?.id).toBe(3);
  });

  it('clamps a carried position within the last 30 s of the sibling to 0', () => {
    const files = [hevc(1, { duration_seconds: 7200 }), file(2, { duration_seconds: 7190 })];
    const p = progress({ media_file_id: 1, position_seconds: 7170, duration_seconds: 7200 });
    expect(startPosition(files[1]!, files, p)).toEqual({ seconds: 0, differentVersion: false });
  });

  it('starts every file at 0 for watched progress or a position below 30 s', () => {
    const files = [hevc(1), file(2)];
    for (const p of [
      progress({ media_file_id: 1, position_seconds: 3000, watched: true }),
      progress({ media_file_id: 1, position_seconds: 29 }),
    ]) {
      for (const x of files) expect(startPosition(x, files, p)).toEqual({ seconds: 0, differentVersion: false });
    }
    expect(startPosition(files[0]!, files, null)).toEqual({ seconds: 0, differentVersion: false });
  });

  it('resumes an explicitly chosen (?file=) matching sibling', () => {
    const files = [hevc(1, { duration_seconds: 7200 }), file(2, { duration_seconds: 7180 })];
    const p = progress({ media_file_id: 1, position_seconds: 3000, duration_seconds: 7200 });
    expect(selectVersion(files, hevcCaps, p)?.id).toBe(1);
    expect(startPosition(files[1]!, files, p)).toEqual({ seconds: 3000, differentVersion: false });
  });
});
