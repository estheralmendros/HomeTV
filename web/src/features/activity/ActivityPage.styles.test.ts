import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// ratings prd2 FR-W13 (addendum): jsdom has no layout, so the stylesheet source is checked.
const CSS = readFileSync(join(__dirname, 'ActivityPage.module.css'), 'utf8');

function rule(selector: string): string {
  const withoutComments = CSS.replace(/\/\*[\s\S]*?\*\//g, '');
  const found = [...withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => (m[1] ?? '').trim() === selector);
  expect(found, `exactly one top-level ${selector} rule`).toHaveLength(1);
  return found[0]![2] ?? '';
}

describe('ActivityPage.module.css', () => {
  it('the 46 px title tile fits its words: small font, 2 px padding, words wrap anywhere', () => {
    const tile = rule('.posterTitle');
    expect(tile).toMatch(/font-size:\s*10px/);
    expect(tile).toMatch(/padding:\s*2px/);
    expect(tile).toMatch(/overflow-wrap:\s*anywhere/);
    expect(tile).toMatch(/background:\s*var\(--gradient-placeholder\)/);
  });

  it('a long title is clamped to 4 lines with an ellipsis instead of being clipped', () => {
    const text = rule('.posterTitleText');
    expect(text).toMatch(/-webkit-line-clamp:\s*4/);
    expect(text).toMatch(/-webkit-box-orient:\s*vertical/);
    expect(text).toMatch(/overflow:\s*hidden/);
  });
});
