import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import {
  audioLabel,
  lastSegmentText,
  sessionStateLabel,
  sessionTitle,
  speedText,
  stopLabel,
  videoLabel,
} from './sessionRows';

describe('sessionTitle', () => {
  it('a film is its title', () => {
    expect(sessionTitle(f.adminSession({ title: 'Dune' }))).toBe('Dune');
  });
  it('an episode is "Show · T4 · E1"', () => {
    const s = f.adminSession({ item_type: 'episode', title: 'Jane the Virgin', season_number: 4, episode_number: 1 });
    expect(sessionTitle(s)).toBe('Jane the Virgin · T4 · E1');
  });
  it('a multi-episode file shows the range', () => {
    const s = f.adminSession({ item_type: 'episode', title: 'Bluey', season_number: 2, episode_number: 3, last_episode_number: 4 });
    expect(sessionTitle(s)).toBe('Bluey · T2 · E3–E4');
  });
  it('an episode without numbers is just the show title', () => {
    expect(sessionTitle(f.adminSession({ item_type: 'episode', title: 'Bluey' }))).toBe('Bluey');
  });
  it('a null title is "Sin identificar"', () => {
    expect(sessionTitle(f.adminSession({ item_type: null, title: null }))).toBe('Sin identificar');
  });
});

describe('labels', () => {
  it.each([['copy', 'Copia'], ['h264', 'Conversión H.264']] as const)('video %s', (a, text) => {
    expect(videoLabel(a)).toBe(text);
  });
  it.each([['copy', 'Copia'], ['aac_stereo', 'AAC estéreo'], ['aac_5_1', 'AAC 5.1'], ['none', 'Sin audio']] as const)('audio %s', (a, text) => {
    expect(audioLabel(a)).toBe(text);
  });
  it.each<[Parameters<typeof sessionStateLabel>[0], string]>([
    [f.adminSession({ state: 'running', video_action: 'copy' }), 'Emitiendo'],
    [f.adminSession({ state: 'running', video_action: 'h264' }), 'Convirtiendo'],
    [f.adminSession({ state: 'throttled', video_action: 'h264' }), 'En espera'],
    [f.adminSession({ state: 'done' }), 'Preparado'],
    [f.adminSession({ state: 'failed' }), 'Error'],
  ])('state %#', (s, text) => {
    expect(sessionStateLabel(s)).toBe(text);
  });
});

describe('lastSegmentText / speedText / stopLabel', () => {
  it('segment index and position', () => {
    expect(lastSegmentText(f.adminSession({ last_segment: 12, position_seconds: 72 }))).toBe('12 · 1:12');
    expect(lastSegmentText(f.adminSession({ last_segment: 210, position_seconds: 1260 }))).toBe('210 · 21:00');
    expect(lastSegmentText(f.adminSession({ last_segment: 800, position_seconds: 3725 }))).toBe('800 · 1:02:05');
  });
  it('segment 0 is still shown', () => {
    expect(lastSegmentText(f.adminSession({ last_segment: 0, position_seconds: 0 }))).toBe('0 · 0:00');
  });
  it('null segment or position → "—"', () => {
    expect(lastSegmentText(f.adminSession({ last_segment: null }))).toBe('—');
    expect(lastSegmentText(f.adminSession({ position_seconds: null }))).toBe('—');
  });
  it('speed has one decimal and "×"; null → "—"', () => {
    expect(speedText(1.43)).toBe('1,4×');
    expect(speedText(2)).toBe('2,0×');
    expect(speedText(null)).toBe('—');
  });
  it('stop label uses the title, else the file name', () => {
    expect(stopLabel(f.adminSession({ title: 'Dune' }))).toBe('Detener la reproducción de Dune');
    expect(stopLabel(f.adminSession({ item_type: null, title: null, file_name: 'x.mkv' }))).toBe('Detener la reproducción de x.mkv');
  });
});
