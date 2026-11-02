import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// Hover underlines `.name`. jsdom does not apply CSS Modules nor `:hover`, so the
// stylesheet source is checked: the underline lives only in the hover rule.
const CSS = readFileSync(join(__dirname, 'CastRow.module.css'), 'utf8');

function rules(css: string): Array<{ selector: string; body: string }> {
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...withoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({
    selector: (m[1] ?? '').trim(),
    body: m[2] ?? '',
  }));
}

describe('CastRow.module.css', () => {
  it('underlines the name only on hover', () => {
    const underlined = rules(CSS)
      .filter((r) => /text-decoration:\s*underline/.test(r.body))
      .map((r) => r.selector);
    expect(underlined).toEqual(['.member:hover .name']);
  });
});
