import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// watchlist prd2 FR-W11 / AC-W13 / AC-W14, ratings prd2 FR-W15: jsdom has no layout, so the
// stylesheet source is checked.
const CSS = readFileSync(join(__dirname, 'NavBar.module.css'), 'utf8');

function rule(selector: string): string {
  const withoutComments = CSS.replace(/\/\*[\s\S]*?\*\//g, '');
  const found = [...withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => (m[1] ?? '').trim() === selector);
  expect(found, `exactly one top-level ${selector} rule`).toHaveLength(1);
  return found[0]![2] ?? '';
}

describe('NavBar.module.css', () => {
  it('the tab bar has five equal columns (the five sections; Buscar lives in the top bar)', () => {
    expect(rule('.tabBar')).toMatch(/grid-template-columns:\s*repeat\(5,\s*1fr\)/);
  });

  it('tabs never truncate: nowrap, centered, no overflow hidden / text-overflow', () => {
    const tab = rule('.tab');
    expect(tab).toMatch(/white-space:\s*nowrap/);
    expect(tab).toMatch(/text-align:\s*center/);
    expect(tab).not.toMatch(/text-overflow/);
    expect(tab).not.toMatch(/overflow:\s*hidden/);
  });

  it('desktop links stay on one line ("Mi lista" is the first two-word label, "Actividad" the longest)', () => {
    expect(rule('.link')).toMatch(/white-space:\s*nowrap/);
  });

  it('the current link is as tall as the others: every link reserves the 2px underline', () => {
    expect(rule('.link')).toMatch(/border-bottom:\s*2px solid transparent/);
    const current = rule(".link[aria-current='page']");
    expect(current).not.toMatch(/border-bottom:/);
    expect(current).toMatch(/border-bottom-color:\s*var\(--color-accent\)/);
  });

  it('the "Buscar" icon link has a 48 × 48 hit area', () => {
    const icon = rule('.searchIcon');
    expect(icon).toMatch(/width:\s*48px/);
    expect(icon).toMatch(/height:\s*48px/);
  });
});

describe('NavSearch.module.css', () => {
  it('the field can never collapse below 160px', () => {
    const css = readFileSync(join(__dirname, '..', 'features', 'search', 'NavSearch.module.css'), 'utf8');
    expect(css).toMatch(/\.form\s*\{[^}]*min-width:\s*160px/);
  });
});
