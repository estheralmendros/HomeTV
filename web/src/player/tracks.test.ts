import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import {
  audioLabel,
  liftCues,
  SUBTITLE_CUE_LINE,
  defaultSubtitle,
  hasImageTracks,
  subtitleLabel,
  textTracks,
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

describe('defaultSubtitle (default flag → forced matching audio → off)', () => {
  it('the default-flagged text track first', () => {
    expect(defaultSubtitle([spa, engDefault], { audioLanguage: 'spa' })).toBe(engDefault);
  });

  it('then a forced track in the audio language', () => {
    expect(defaultSubtitle([spa, engForced], { audioLanguage: 'eng' })).toBe(engForced);
    expect(defaultSubtitle([spa, engForced], { audioLanguage: 'spa' })).toBeNull();
  });

  it('else none; image tracks never count', () => {
    expect(defaultSubtitle([spa, eng], { audioLanguage: 'eng' })).toBeNull();
    expect(defaultSubtitle([pgs], { audioLanguage: 'spa' })).toBeNull();
  });

  it('the stored-language step is gone: hometv.subtitleLang is never read', () => {
    window.localStorage.setItem('hometv.subtitleLang.1', 'spa');
    expect(defaultSubtitle([engDefault, spa], { audioLanguage: 'eng' })).toBe(engDefault);
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

describe('liftCues', () => {
  it('moves only auto-positioned, line-snapped cues; file-positioned cues are never touched', () => {
    const auto = { line: 'auto' as number | 'auto', snapToLines: true };
    const placed = { line: 1 as number | 'auto', snapToLines: true };
    const percent = { line: 'auto' as number | 'auto', snapToLines: false };
    const lifted = new WeakSet<object>();
    liftCues([auto, placed, percent], SUBTITLE_CUE_LINE, lifted);
    expect([auto.line, placed.line, percent.line]).toEqual([-3, 1, 'auto']);
    expect(() => liftCues(null, -3, lifted)).not.toThrow();
  });

  it('re-lifts the cues it lifted when the line changes (-4, then -5); a numeric cue stays', () => {
    const auto = { line: 'auto' as number | 'auto', snapToLines: true };
    const placed = { line: -2 as number | 'auto', snapToLines: true };
    const lifted = new WeakSet<object>();
    liftCues([auto, placed], -4, lifted);
    expect([auto.line, placed.line]).toEqual([-4, -2]);
    liftCues([auto, placed], -5, lifted);
    expect([auto.line, placed.line]).toEqual([-5, -2]);
  });

  it('SUBTITLE_CUE_LINE is the "Baja" line (-3)', () => {
    expect(SUBTITLE_CUE_LINE).toBe(-3);
  });
});
