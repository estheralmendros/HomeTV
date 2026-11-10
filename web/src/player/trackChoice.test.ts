// The matching rule (prd.md FR-3..FR-5a) driven by the shared case table, read by path (never
// imported: the Docker web build context has no `shared/`).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { AudioTrack, SubtitleTrack, TrackChoice } from '@/api/types';
import * as f from '../../tests/fixtures';
import {
  audioChoiceOf,
  languageKey,
  matchAudio,
  matchSubtitle,
  matchTrackChoice,
  sameAudioChoice,
  sameLanguage,
  subtitleChoiceOf,
  withAudio,
  withSubtitles,
} from './trackChoice';

interface Expected {
  audio_stream_index?: number | null;
  subtitle_id?: string | null;
}

interface Case {
  name: string;
  mode: 'direct' | 'hls';
  choice: TrackChoice | null;
  pick?: Partial<AudioTrack>;
  audio_tracks: Partial<AudioTrack>[];
  subtitle_tracks: Partial<SubtitleTrack>[];
  expected: Required<Expected>;
  expected_web?: Expected;
  expected_tv?: Expected;
}

const table = JSON.parse(readFileSync(join(__dirname, '../../../shared/track-choice-cases.json'), 'utf8')) as {
  version: number;
  cases: Case[];
};

describe('shared/track-choice-cases.json', () => {
  it('holds at least the 24 table rows', () => {
    expect(table.cases.length).toBeGreaterThanOrEqual(24);
  });

  it.each(table.cases.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const audio = c.audio_tracks.map((t) => f.audioTrack(t));
    const subtitles = c.subtitle_tracks.map((t) => f.subtitleTrack(t));
    if (c.pick) expect(audioChoiceOf(f.audioTrack(c.pick))).toEqual(c.choice?.audio);
    const expected = { ...c.expected, ...c.expected_web };
    expect(matchTrackChoice(c.choice, audio, subtitles)).toEqual({
      audioStreamIndex: expected.audio_stream_index,
      subtitleId: expected.subtitle_id,
    });
  });
});

describe('languageKey / sameLanguage (FR-3)', () => {
  it.each([
    ['eng', 'en'],
    [' ENG ', 'en'],
    ['fre', 'fr'],
    ['fra', 'fr'],
    ['pt-BR', 'pt'],
    ['zh_Hans', 'zh'],
    ['und', 'und'],
    ['en', 'en'],
    ['xyz', 'xyz'],
    ['', null],
    ['  ', null],
    [null, null],
    [undefined, null],
  ])('%s → %s', (code, key) => {
    expect(languageKey(code)).toBe(key);
  });

  it('two languages are the same only when both keys exist and are equal', () => {
    expect(sameLanguage('fre', 'fra')).toBe(true);
    expect(sameLanguage('pt-BR', 'por')).toBe(true);
    expect(sameLanguage('und', 'und')).toBe(true);
    expect(sameLanguage('spa', 'und')).toBe(false);
    expect(sameLanguage('', '')).toBe(false);
    expect(sameLanguage(null, null)).toBe(false);
  });
});

describe('matchAudio / matchSubtitle edges', () => {
  it('no part or no tracks → null', () => {
    expect(matchAudio(null, [f.audioTrack()])).toBeNull();
    expect(matchAudio(f.audioChoice(), [])).toBeNull();
  });

  it('a subtitle part with no visible track of its language falls back to the default rule', () => {
    const tracks = [f.subtitleTrack({ id: 'emb-4', language: 'spa', default: true })];
    expect(matchSubtitle(f.subtitleChoice({ language: 'ita' }), tracks, 'eng')).toBe('emb-4');
    expect(matchSubtitle(null, [f.subtitleTrack({ id: 'emb-5', language: 'eng', forced: true })], 'eng')).toBe('emb-5');
  });

  it('mode off → null even with a default track', () => {
    const tracks = [f.subtitleTrack({ id: 'emb-4', default: true })];
    expect(matchSubtitle(f.subtitleChoice({ mode: 'off', language: null, source: null, kind: null }), tracks, null)).toBeNull();
  });
});

describe('building a choice from a pick (FR-5a)', () => {
  it('audioChoiceOf: trimmed language (blank → und), title, channels 1..32 else null', () => {
    expect(audioChoiceOf(f.audioTrack({ language: ' eng ', title: 'Surround', channels: 6 }))).toEqual({
      language: 'eng',
      title: 'Surround',
      channels: 6,
    });
    expect(audioChoiceOf(f.audioTrack({ language: '  ', channels: 0 }))).toEqual({ language: 'und', title: null, channels: null });
    expect(audioChoiceOf(f.audioTrack({ channels: 33 })).channels).toBeNull();
    expect(audioChoiceOf(f.audioTrack({ channels: null })).channels).toBeNull();
  });

  it('a title over 1000 characters is cut to 1000', () => {
    expect(audioChoiceOf(f.audioTrack({ title: 'x'.repeat(1200) })).title).toHaveLength(1000);
    expect(subtitleChoiceOf(f.subtitleTrack({ title: 'y'.repeat(1001) })).title).toHaveLength(1000);
  });

  it('a language over 16 characters (after the trim) is cut to 16, the server cap', () => {
    expect(audioChoiceOf(f.audioTrack({ language: ` ${'a'.repeat(20)} ` })).language).toBe('a'.repeat(16));
    expect(subtitleChoiceOf(f.subtitleTrack({ language: 'b'.repeat(17) })).language).toBe('b'.repeat(16));
    expect(audioChoiceOf(f.audioTrack({ language: 'zh-Hant-TW' })).language).toBe('zh-Hant-TW');
  });

  it('subtitleChoiceOf: null → off; a track → its traits', () => {
    expect(subtitleChoiceOf(null)).toEqual({
      mode: 'off',
      language: null,
      title: null,
      forced: false,
      hearing_impaired: false,
      source: null,
      kind: null,
    });
    expect(subtitleChoiceOf(f.subtitleTrack({ language: '', title: 'SDH', hearing_impaired: true, source: 'external' }))).toEqual({
      mode: 'track',
      language: 'und',
      title: 'SDH',
      forced: false,
      hearing_impaired: true,
      source: 'external',
      kind: 'text',
    });
  });

  it('withAudio / withSubtitles replace one part and keep the other', () => {
    const subs = f.subtitleChoice();
    const held: TrackChoice = { audio: f.audioChoice(), subtitles: subs };
    expect(withAudio(held, f.audioChoice({ language: 'spa' }))).toEqual({ audio: f.audioChoice({ language: 'spa' }), subtitles: subs });
    expect(withSubtitles(held, subtitleChoiceOf(null))).toEqual({ audio: f.audioChoice(), subtitles: subtitleChoiceOf(null) });
    expect(withAudio(null, f.audioChoice())).toEqual({ audio: f.audioChoice(), subtitles: null });
    expect(withSubtitles(null, subs)).toEqual({ audio: null, subtitles: subs });
  });

  it('sameAudioChoice: both non-null and every field equal (null = null)', () => {
    expect(sameAudioChoice(f.audioChoice(), f.audioChoice())).toBe(true);
    expect(sameAudioChoice(f.audioChoice({ title: null, channels: null }), f.audioChoice({ title: null, channels: null }))).toBe(true);
    expect(sameAudioChoice(f.audioChoice(), f.audioChoice({ channels: 2 }))).toBe(false);
    expect(sameAudioChoice(f.audioChoice(), f.audioChoice({ title: 'x' }))).toBe(false);
    expect(sameAudioChoice(f.audioChoice(), null)).toBe(false);
    expect(sameAudioChoice(undefined, undefined)).toBe(false);
  });
});
