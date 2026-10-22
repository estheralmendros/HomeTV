import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { episodeLabel, episodeLine, episodeTitle } from './episodes';

describe('episode texts', () => {
  it('uses the title, else "Episodio N"', () => {
    expect(episodeTitle(f.episodeSummary())).toBe('Office Olympics');
    expect(episodeTitle(f.episodeSummary({ title: null, episode_number: 4 }))).toBe('Episodio 4');
    expect(episodeTitle(f.episodeSummary({ title: '  ', episode_number: 4 }))).toBe('Episodio 4');
  });

  it('builds "T2 · E3 · Título" and "The Office · T2 · E3 · Título"', () => {
    expect(episodeLine(f.episodeSummary())).toBe('T2 · E3 · Office Olympics');
    expect(episodeLabel(f.episodeSummary({ title: null }))).toBe('The Office · T2 · E3 · Episodio 3');
  });
});
