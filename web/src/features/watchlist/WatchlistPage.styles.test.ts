import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// jsdom has no layout, so the stylesheet source is checked (as in NavBar.styles.test.ts).
const CSS = readFileSync(join(__dirname, 'WatchlistPage.module.css'), 'utf8');

function rule(selector: string): string {
  const withoutComments = CSS.replace(/\/\*[\s\S]*?\*\//g, '');
  const found = [...withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) =>
    (m[1] ?? '').split(',').some((s) => s.trim() === selector),
  );
  expect(found, `exactly one top-level rule for ${selector}`).toHaveLength(1);
  return found[0]![2] ?? '';
}

describe('WatchlistPage.module.css', () => {
  it('the pressed type button is filled with the accent, not the hover background', () => {
    const pressed = rule(".type[aria-pressed='true']");
    expect(pressed).toMatch(/background:\s*var\(--color-accent\)/);
    expect(pressed).toMatch(/border-color:\s*var\(--color-accent\)/);
    expect(pressed).not.toMatch(/--color-border/);
  });

  it('hovering the pressed button keeps the accent fill (global .button:hover is more specific)', () => {
    expect(rule(".type[aria-pressed='true']:hover:not(:disabled)")).toMatch(/background:\s*var\(--color-accent\)/);
  });
});
