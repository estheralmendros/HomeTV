import { describe, expect, it } from 'vitest';
import { playerUrl } from './playerUrl';

describe('playerUrl', () => {
  it('builds /watch URLs with optional file and t (t=0 kept)', () => {
    expect(playerUrl('film', 12)).toBe('/watch/film/12');
    expect(playerUrl('episode', 345)).toBe('/watch/episode/345');
    expect(playerUrl('film', 12, { file: 91 })).toBe('/watch/film/12?file=91');
    expect(playerUrl('film', 12, { file: 91, t: 0 })).toBe('/watch/film/12?file=91&t=0');
    expect(playerUrl('film', 12, { file: null, t: null })).toBe('/watch/film/12');
  });
});
