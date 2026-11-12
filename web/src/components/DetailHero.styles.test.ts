import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// Description layout: jsdom has no layout, so the stylesheet source is checked.
const CSS = readFileSync(join(__dirname, 'DetailHero.module.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

function block(source: string, selector: string): string {
  const found = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => (m[1] ?? '').trim() === selector);
  expect(found, `exactly one ${selector} rule`).toHaveLength(1);
  return found[0]![2] ?? '';
}

function phoneMedia(): string {
  const start = CSS.indexOf('@media (max-width: 599px)');
  expect(start).toBeGreaterThan(-1);
  return CSS.slice(start);
}

describe('DetailHero.module.css: the description (films and shows)', () => {
  it('ends at about 3/4 of the window (75vw minus the gutter, poster and gap), never under 45ch', () => {
    const overview = block(CSS.slice(0, CSS.indexOf('@media')), '.overview');
    expect(overview).toContain('max-width: max(45ch, calc(75vw - var(--page-gutter) - 200px - var(--space-5)));');
  });

  it('is justified with Spanish hyphenation (html lang="es")', () => {
    const overview = block(CSS.slice(0, CSS.indexOf('@media')), '.overview');
    expect(overview).toMatch(/text-align:\s*justify/);
    expect(overview).toMatch(/hyphens:\s*auto/);
  });

  it('takes the full width on phones (< 600 px, poster stacked above)', () => {
    expect(block(phoneMedia(), '.overview')).toMatch(/max-width:\s*none/);
  });
});
