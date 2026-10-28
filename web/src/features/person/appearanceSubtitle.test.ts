import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { appearanceSubtitle } from './appearanceSubtitle';

describe('appearanceSubtitle', () => {
  it.each([
    ['film with a character', f.filmAppearance({ character: 'Paul Atreides' }), 'como Paul Atreides'],
    ['film without a character', f.filmAppearance({ character: null }), null],
    ['show with a character', f.showAppearance({ character: 'Pam Beesly' }), 'como Pam Beesly'],
    ['show without a character', f.showAppearance({ character: null }), null],
  ])('%s', (_case, appearance, subtitle) => {
    expect(appearanceSubtitle(appearance)).toBe(subtitle);
  });
});
