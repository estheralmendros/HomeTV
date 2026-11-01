import { describe, expect, it } from 'vitest';
import { S } from '@/strings.es';
import { roundToHalf, starFills, starsFromPointer, stepStars } from './stars';

describe('starFills', () => {
  it.each<[number | null, string]>([
    [null, 'eeeee'],
    [0.5, 'heeee'],
    [1, 'feeee'],
    [3.5, 'fffhe'],
    [4.5, 'ffffh'],
    [5, 'fffff'],
  ])('%s → %s', (value, expected) => {
    const code = { full: 'f', half: 'h', empty: 'e' } as const;
    expect(starFills(value).map((fill) => code[fill]).join('')).toBe(expected);
  });
});

describe('stepStars', () => {
  it.each<[number | null, 1 | -1, number]>([
    [null, 1, 0.5],
    [null, -1, 0.5],
    [0.5, 1, 1],
    [0.5, -1, 0.5],
    [4, 1, 4.5],
    [4.5, 1, 5],
    [5, 1, 5],
    [3, -1, 2.5],
  ])('%s %s → %s', (value, delta, expected) => {
    expect(stepStars(value, delta)).toBe(expected);
  });

  it('nine steps up from empty give 4,5 (US-2)', () => {
    let value: number | null = null;
    for (let i = 0; i < 9; i += 1) value = stepStars(value, 1);
    expect(value).toBe(4.5);
  });
});

describe('starsFromPointer', () => {
  it.each<[number, number, number]>([
    [3, 0.2, 3.5],
    [3, 0.49, 3.5],
    [3, 0.5, 4],
    [3, 0.9, 4],
    [0, 0, 0.5],
    [4, 1, 5],
  ])('star %s at %s → %s', (index, fraction, expected) => {
    expect(starsFromPointer(index, fraction)).toBe(expected);
  });
});

describe('roundToHalf (average icons)', () => {
  it.each<[number, number]>([
    [3.5, 3.5],
    [3.7, 3.5],
    [3.75, 4],
    [4.2, 4],
    [4.3, 4.5],
    [1.1, 1],
  ])('%s → %s', (value, expected) => {
    expect(roundToHalf(value)).toBe(expected);
  });
});

describe('S.ratings.stars (text equivalent)', () => {
  it.each<[number, string]>([
    [4.5, '4,5 estrellas'],
    [4, '4 estrellas'],
    [1, '1 estrella'],
    [0.5, '0,5 estrellas'],
    [2, '2 estrellas'],
  ])('%s → %s', (value, expected) => {
    expect(S.ratings.stars(value)).toBe(expected);
  });
});
