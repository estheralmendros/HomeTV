import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// jsdom has no layout, so the stylesheet source is checked.
const CSS = readFileSync(join(__dirname, 'FixMatchDialog.module.css'), 'utf8');

describe('FixMatchDialog.module.css', () => {
  it('the year field fits four digits plus its horizontal padding and border', () => {
    const year = /\.year\s*\{([^}]*)\}/.exec(CSS)?.[1] ?? '';
    expect(year).toMatch(/width:\s*calc\(5ch \+ 2 \* var\(--space-3\) \+ 2px\)/);
  });
});
