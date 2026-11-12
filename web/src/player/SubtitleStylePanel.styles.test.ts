import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// The style panel layout (W-AC-16): jsdom has no layout, so the stylesheet source is checked.
const CSS = readFileSync(join(__dirname, 'Player.module.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Body of the rule for `selector` inside `scope` (default: top level, outside @media blocks). */
function rule(selector: string, media?: string): string {
  const source = media === undefined ? CSS.replace(/@media[^{]+\{(?:[^{}]*\{[^{}]*\})*\s*\}/g, '') : mediaBlock(media);
  const found = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => (m[1] ?? '').trim() === selector);
  expect(found, `exactly one ${selector} rule${media ? ` in ${media}` : ''}`).toHaveLength(1);
  return found[0]![2] ?? '';
}

function mediaBlock(query: string): string {
  const start = CSS.indexOf(`@media ${query}`);
  expect(start, `@media ${query}`).toBeGreaterThanOrEqual(0);
  let depth = 0;
  let i = CSS.indexOf('{', start);
  const from = i + 1;
  for (; i < CSS.length; i += 1) {
    if (CSS[i] === '{') depth += 1;
    else if (CSS[i] === '}' && --depth === 0) break;
  }
  return CSS.slice(from, i);
}

describe('SubtitleStylePanel CSS', () => {
  it('≥ 600 px: anchored top right, 360 px wide, scrolls above the control bar', () => {
    const panel = rule('.stylePanel');
    expect(panel).toMatch(/top:\s*16px/);
    expect(panel).toMatch(/right:\s*16px/);
    expect(panel).toMatch(/width:\s*360px/);
    expect(panel).toMatch(/max-height:\s*calc\(100% - var\(--player-controls-h\) - 32px\)/);
    expect(panel).toMatch(/overflow-y:\s*auto/);
  });

  it('< 600 px: a sheet of at most 45 % height that scrolls, no fixed width over 328 px', () => {
    const sheet = rule('.styleSheet', '(max-width: 599px)');
    expect(sheet).toMatch(/max-height:\s*45%/);
    expect(sheet).toMatch(/overflow-y:\s*auto/);
    expect(sheet).toMatch(/left:\s*0/);
    expect(sheet).toMatch(/right:\s*0/);
    expect(sheet).toMatch(/width:\s*auto/);
    for (const [, px] of sheet.matchAll(/(?:^|[\s;])(?:min-)?width:\s*(\d+)px/g)) expect(Number(px)).toBeLessThanOrEqual(328);
  });

  it('options wrap', () => {
    expect(rule('.styleOptions')).toMatch(/flex-wrap:\s*wrap/);
  });

  it('the sample line is centred and never takes pointer events', () => {
    const sample = rule('.styleSample');
    expect(sample).toMatch(/text-align:\s*center/);
    expect(sample).toMatch(/pointer-events:\s*none/);
  });
});
