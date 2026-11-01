import { describe, expect, it } from 'vitest';
import {
  episodeCode,
  formatActivityTime,
  episodeShort,
  formatBytes,
  formatCount,
  formatDate,
  formatDuration,
  formatRating,
  formatRelative,
  formatRuntime,
  formatSpokenDuration,
  parseEpisodeList,
  seasonLabel,
} from './format';

describe('format (es-ES)', () => {
  it('formatDuration: clock times', () => {
    expect(formatDuration(4325)).toBe('1:12:05');
    expect(formatDuration(751)).toBe('12:31');
    expect(formatDuration(5)).toBe('0:05');
    expect(formatDuration(751, { withHours: true })).toBe('0:12:31');
    expect(formatDuration(-3)).toBe('0:00');
    expect(formatDuration(Number.NaN)).toBe('0:00');
  });

  it('formatSpokenDuration', () => {
    expect(formatSpokenDuration(751)).toBe('12 minutos y 31 segundos');
    expect(formatSpokenDuration(1320)).toBe('22 minutos');
    expect(formatSpokenDuration(4325)).toBe('1 hora, 12 minutos y 5 segundos');
    expect(formatSpokenDuration(61)).toBe('1 minuto y 1 segundo');
    expect(formatSpokenDuration(7200)).toBe('2 horas');
    expect(formatSpokenDuration(0)).toBe('0 segundos');
  });

  it('formatRuntime', () => {
    expect(formatRuntime(155)).toBe('2 h 35 min');
    expect(formatRuntime(45)).toBe('45 min');
    expect(formatRuntime(120)).toBe('2 h');
  });

  it('formatDate: air dates without timezone shifts', () => {
    expect(formatDate('2005-10-04')).toBe('4 oct 2005');
    expect(formatDate('2005-09-20')).toMatch(/^20 sept? 2005$/);
    expect(formatDate('2026-10-22T10:00:00Z')).toBe('22 oct 2026');
  });

  it('formatRelative', () => {
    const now = Date.parse('2026-10-21T18:10:00Z');
    expect(formatRelative('2026-10-21T18:08:00Z', now)).toBe('hace 2 minutos');
    expect(formatRelative('2026-10-21T16:10:00Z', now)).toBe('hace 2 horas');
    expect(formatRelative('2026-10-19T18:10:00Z', now)).toBe('anteayer');
    expect(formatRelative('2026-10-21T18:09:30Z', now)).toBe('hace 30 segundos');
  });

  it('numbers: grouping always, decimal comma', () => {
    expect(formatCount(1234)).toBe('1.234');
    expect(formatCount(1012)).toBe('1.012');
    expect(formatCount(12)).toBe('12');
    expect(formatRating(7.8)).toBe('7,8');
    expect(formatRating(8)).toBe('8,0');
  });

  it('formatBytes', () => {
    expect(formatBytes(58_100_000_000)).toBe('58,1 GB');
    expect(formatBytes(2_100_000_000)).toBe('2,1 GB');
    expect(formatBytes(400_000_000)).toBe('400 MB');
    expect(formatBytes(1_500)).toBe('1,5 KB');
    expect(formatBytes(512)).toBe('512 B');
  });

  it('episode codes', () => {
    expect(episodeCode(2, 3)).toBe('T2 · E3');
    expect(episodeCode(2, 3, 4)).toBe('T2 · E3–E4');
    expect(episodeCode(2, 3, 3)).toBe('T2 · E3');
    expect(episodeShort(3)).toBe('E3');
  });

  it('seasonLabel: name, else Temporada N / Especiales', () => {
    expect(seasonLabel(2, 'Segunda temporada')).toBe('Segunda temporada');
    expect(seasonLabel(2, null)).toBe('Temporada 2');
    expect(seasonLabel(0, null)).toBe('Especiales');
  });

  it('parseEpisodeList', () => {
    expect(parseEpisodeList('16-17, 3')).toEqual([3, 16, 17]);
    expect(parseEpisodeList('3')).toEqual([3]);
    expect(parseEpisodeList(' 5 ,5, 4–6 ')).toEqual([4, 5, 6]);
    expect(() => parseEpisodeList('')).toThrow();
    expect(() => parseEpisodeList('a')).toThrow();
    expect(() => parseEpisodeList('5-3')).toThrow();
    expect(() => parseEpisodeList('0')).toThrow();
  });
});

describe('formatActivityTime (local time zone)', () => {
  /** A local wall-clock time as the ISO string the server sends. */
  const at = (y: number, m: number, d: number, h = 0, min = 0, s = 0) => new Date(y, m - 1, d, h, min, s).toISOString();
  const now = new Date(2026, 9, 31, 15, 30, 0);

  it.each<[string, string, Date]>([
    ['30 s ago', at(2026, 10, 31, 15, 29, 30), now],
    ['in the future', at(2026, 10, 31, 15, 45), now],
  ])('%s → "ahora mismo"', (_case, iso, when) => {
    expect(formatActivityTime(iso, when)).toBe('ahora mismo');
  });

  it.each<[string, string]>([
    [at(2026, 10, 31, 15, 29, 0), 'hace 1 minuto'],
    [at(2026, 10, 31, 15, 25), 'hace 5 minutos'],
    [at(2026, 10, 31, 14, 30, 1), 'hace 59 minutos'],
    [at(2026, 10, 31, 14, 30), 'hace 1 hora'],
    [at(2026, 10, 31, 13, 0), 'hace 2 horas'],
    [at(2026, 10, 31, 0, 1), 'hace 15 horas'],
    [at(2026, 10, 30, 23, 59), 'ayer'],
    [at(2026, 10, 30, 0, 0), 'ayer'],
    [at(2026, 10, 29, 23, 59), '29 oct'],
    [at(2026, 10, 12, 9, 0), '12 oct'],
    [at(2026, 1, 3, 9, 0), '3 ene'],
    [at(2025, 10, 12, 9, 0), '12 oct 2025'],
  ])('%s → %s', (iso, expected) => {
    expect(formatActivityTime(iso, now)).toBe(expected);
  });

  it('00:05 vs 23:50 the day before → "ayer" (calendar days, not 24 h)', () => {
    expect(formatActivityTime(at(2026, 10, 30, 23, 50), new Date(2026, 9, 31, 0, 5))).toBe('ayer');
  });

  it('Dec 31 seen on Jan 1 → "ayer"; Dec 30 seen on Jan 1 → with the year', () => {
    const newYear = new Date(2027, 0, 1, 10, 0);
    expect(formatActivityTime(at(2026, 12, 31, 22, 0), newYear)).toBe('ayer');
    expect(formatActivityTime(at(2026, 12, 30, 22, 0), newYear)).toBe('30 dic 2026');
  });

  it.each(['', 'not a date', '2026-13-45T99:00:00Z'])('an unparseable timestamp %j → "" (never throws)', (iso) => {
    expect(formatActivityTime(iso, now)).toBe('');
  });

  it('defaults now to the current time', () => {
    expect(formatActivityTime(new Date().toISOString())).toBe('ahora mismo');
  });
});
