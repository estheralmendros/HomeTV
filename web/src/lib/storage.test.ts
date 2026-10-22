import { describe, expect, it, vi } from 'vitest';
import { readJson, readString, removeKey, writeJson, writeString } from './storage';

describe('storage (localStorage, never throws)', () => {
  it('round-trips strings and JSON', () => {
    writeString('k', 'v');
    expect(readString('k')).toBe('v');
    writeJson('j', { a: 1 });
    expect(readJson('j')).toEqual({ a: 1 });
    removeKey('k');
    expect(readString('k')).toBeNull();
  });

  it('returns null for invalid JSON', () => {
    localStorage.setItem('bad', '{');
    expect(readJson('bad')).toBeNull();
  });

  it('swallows storage errors', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(readString('k')).toBeNull();
    expect(() => writeString('k', 'v')).not.toThrow();
    expect(() => removeKey('k')).not.toThrow();
  });
});
