import { describe, expect, it } from 'vitest';
import { ageOn, formatLongDate, isValidIsoDate, lifeSpanText, todayIso } from './lifeSpan';

describe('formatLongDate', () => {
  it.each([
    ['1992-10-08', '8 de octubre de 1992'],
    ['2010-05-03', '3 de mayo de 2010'],
    ['1995-12-27', '27 de diciembre de 1995'],
    ['2000-01-01', '1 de enero de 2000'],
  ])('%s → %s (UTC, never shifted a day)', (iso, text) => {
    expect(formatLongDate(iso)).toBe(text);
  });
});

describe('isValidIsoDate', () => {
  it.each([
    ['1995-12-27', true],
    ['2000-02-29', true],
    ['2021-02-29', false],
    ['2021-02-30', false],
    ['2020-13-45', false],
    ['2020-00-10', false],
    ['1995-12-27T00:00:00', false],
    ['27/12/1995', false],
    ['', false],
  ])('%s → %s', (iso, valid) => {
    expect(isValidIsoDate(iso)).toBe(valid);
  });
});

describe('ageOn', () => {
  it.each<[string, string, number | null]>([
    ['1995-12-27', '2026-10-27', 30],
    ['1995-12-27', '2026-12-26', 30],
    ['1995-12-27', '2026-12-27', 31],
    ['1995-12-27', '2026-12-28', 31],
    ['1932-10-08', '2010-05-03', 77],
    ['2000-02-29', '2027-02-28', 26],
    ['2000-02-29', '2027-03-01', 27],
    ['2000-02-29', '2028-02-28', 27],
    ['2000-02-29', '2028-02-29', 28],
    ['2026-10-27', '2026-10-27', 0],
    ['2026-10-28', '2026-10-27', null],
    ['2010-05-03', '1932-10-08', null],
    ['nope', '2026-10-27', null],
    ['1995-12-27', '2026-02-30', null],
  ])('%s → %s: %s', (birth, on, age) => {
    expect(ageOn(birth, on)).toBe(age);
  });
});

describe('todayIso', () => {
  it('is the local calendar day, zero-padded', () => {
    expect(todayIso(new Date(2026, 9, 27, 23, 59))).toBe('2026-10-27');
    expect(todayIso(new Date(2026, 0, 5, 0, 0))).toBe('2026-01-05');
  });
});

describe('lifeSpanText', () => {
  const today = '2026-10-27';
  it.each<[string, string | null, string | null, string | null]>([
    ['living', '1995-12-27', null, '27 de diciembre de 1995 (30 años)'],
    ['living, 1 year old', '2025-10-01', null, '1 de octubre de 2025 (1 año)'],
    ['deceased', '1932-10-08', '2010-05-03', '8 de octubre de 1932 – 3 de mayo de 2010 (77 años)'],
    ['deathday only', null, '2010-05-03', '– 3 de mayo de 2010'],
    ['neither', null, null, null],
    ['deathday before birthday: no age', '2010-05-03', '1932-10-08', '3 de mayo de 2010 – 8 de octubre de 1932'],
    ['future birthday: the date alone', '2027-01-01', null, '1 de enero de 2027'],
    ['invalid birthday counts as null', '2021-02-30', null, null],
    ['invalid birthday, valid deathday', '2020-13-45', '2010-05-03', '– 3 de mayo de 2010'],
    ['invalid deathday, valid birthday', '1995-12-27', 'soon', '27 de diciembre de 1995 (30 años)'],
  ])('%s', (_case, birthday, deathday, text) => {
    expect(lifeSpanText(birthday, deathday, today)).toBe(text);
  });
});
