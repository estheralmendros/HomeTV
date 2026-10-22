import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import {
  audioLabel,
  liftCues,
  SUBTITLE_CUE_LINE,
  defaultSubtitle,
  hasImageTracks,
  readSubtitleLang,
  subtitleLabel,
  SUBTITLES_OFF,
  textTracks,
  writeSubtitleLang,
} from './tracks';

const spa = f.subtitleTrack({ id: 'emb-3', language: 'spa' });
const eng = f.subtitleTrack({ id: 'emb-4', language: 'eng' });
const engForced = f.subtitleTrack({ id: 'emb-5', language: 'eng', forced: true });
const engDefault = f.subtitleTrack({ id: 'emb-6', language: 'eng', default: true });
const pgs = f.subtitleTrack({ id: 'emb-7', kind: 'image', language: 'spa', default: true });

describe('textTracks / hasImageTracks', () => {
  it('drops image tracks', () => {
    expect(textTracks([spa, pgs, eng])).toEqual([spa, eng]);
    expect(hasImageTracks([spa, pgs])).toBe(true);
    expect(hasImageTracks([spa])).toBe(false);
  });
});

describe('defaultSubtitle (stored language → default flag → forced matching audio → off)', () => {
  it('uses the stored language when a track has it', () => {
    expect(defaultSubtitle([engDefault, spa], { storedLang: 'spa', audioLanguage: 'eng' })).toBe(spa);
  });

  it('stored "off" means none, even with a default track', () => {
    expect(defaultSubtitle([engDefault, spa], { storedLang: SUBTITLES_OFF, audioLanguage: 'eng' })).toBeNull();
  });

  it('a stored language without a track falls through to the default flag', () => {
    expect(defaultSubtitle([spa, engDefault], { storedLang: 'fre', audioLanguage: null })).toBe(engDefault);
  });

  it('then a forced track in the audio language', () => {
    expect(defaultSubtitle([spa, engForced], { storedLang: null, audioLanguage: 'eng' })).toBe(engForced);
    expect(defaultSubtitle([spa, engForced], { storedLang: null, audioLanguage: 'spa' })).toBeNull();
  });

  it('else none; image tracks never count', () => {
    expect(defaultSubtitle([spa, eng], { storedLang: null, audioLanguage: 'eng' })).toBeNull();
    expect(defaultSubtitle([pgs], { storedLang: 'spa', audioLanguage: 'spa' })).toBeNull();
  });
});

describe('labels', () => {
  it('subtitle: language name, title and tags', () => {
    expect(subtitleLabel(spa)).toBe('Español');
    expect(subtitleLabel(f.subtitleTrack({ language: 'eng', title: 'Commentary', forced: true }))).toBe(
      'Inglés · Commentary · Forzados',
    );
    expect(
      subtitleLabel(f.subtitleTrack({ language: 'eng', hearing_impaired: true, source: 'external' })),
    ).toBe('Inglés · SDH · Externo');
    expect(subtitleLabel(f.subtitleTrack({ language: 'und' }))).toBe('Desconocido');
  });

  it('audio: language · codec channels · title', () => {
    expect(audioLabel(f.audioTrack({ language: 'eng', codec: 'eac3', channels: 6, title: 'Surround' }))).toBe(
      'Inglés · EAC3 5.1 · Surround',
    );
    expect(audioLabel(f.audioTrack({ language: 'spa', codec: 'ac3', channels: 2 }))).toBe('Español · AC3 2.0');
    expect(audioLabel(f.audioTrack({ language: 'spa', codec: 'truehd', channels: 8 }))).toBe('Español · TRUEHD 7.1');
    expect(audioLabel(f.audioTrack({ language: 'jpn', codec: 'aac', channels: 1 }))).toBe('Japonés · AAC 1.0');
    expect(audioLabel(f.audioTrack({ language: 'und', codec: 'aac', channels: null }))).toBe('Desconocido · AAC');
  });
});

describe('stored subtitle choice (hometv.subtitleLang.<pid>)', () => {
  it('round-trips per profile', () => {
    expect(readSubtitleLang(1)).toBeNull();
    writeSubtitleLang(1, 'spa');
    writeSubtitleLang(2, SUBTITLES_OFF);
    expect(readSubtitleLang(1)).toBe('spa');
    expect(readSubtitleLang(2)).toBe('off');
    expect(window.localStorage.getItem('hometv.subtitleLang.1')).toBe('spa');
  });
});

describe('liftCues', () => {
  it('moves only auto-positioned, line-snapped cues to SUBTITLE_CUE_LINE', () => {
    const auto = { line: 'auto' as const, snapToLines: true };
    const placed = { line: 1, snapToLines: true };
    const percent = { line: 'auto' as const, snapToLines: false };
    liftCues([auto, placed, percent]);
    expect([auto.line, placed.line, percent.line]).toEqual([SUBTITLE_CUE_LINE, 1, 'auto']);
    expect(() => liftCues(null)).not.toThrow();
  });
});
