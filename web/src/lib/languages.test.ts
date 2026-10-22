import { describe, expect, it } from 'vitest';
import { languageName, toBcp47 } from './languages';

describe('languageName', () => {
  it.each([
    ['eng', 'Inglés'],
    ['spa', 'Español'],
    ['baq', 'Euskera'],
    ['cat', 'Catalán'],
    ['fre', 'Francés'],
    ['ger', 'Alemán'],
    ['jpn', 'Japonés'],
    ['und', 'Desconocido'],
    ['xyz', 'XYZ'],
  ])('%s → %s', (code, name) => {
    expect(languageName(code)).toBe(name);
  });

  it('maps 639-2/B and /T codes to BCP 47', () => {
    expect(toBcp47('chi')).toBe('zh');
    expect(toBcp47('zho')).toBe('zh');
    expect(toBcp47('dut')).toBe('nl');
    expect(toBcp47('ENG')).toBe('en');
    expect(toBcp47('es')).toBe('es');
    expect(toBcp47('xyz')).toBeNull();
  });
});
