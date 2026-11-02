import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// Stars are a non-text graphic (WCAG 1.4.11, ≥ 3:1). The web has one
// (dark) theme, so the check runs against its three backgrounds, from the token values.
const CSS = readFileSync(join(__dirname, 'tokens.css'), 'utf8');

function token(name: string): string {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`).exec(CSS);
  if (!m) throw new Error(`--${name} is not a #rrggbb token`);
  return m[1]!;
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

/** HSL hue in degrees (0–360) of a #rrggbb colour. */
function hue(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255) as [number, number, number];
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  if (d === 0) return 0;
  const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return (h * 60 + 360) % 360;
}

describe('tokens.css', () => {
  it('computes WCAG contrast ratios', () => {
    expect(contrast('#ffffff', '#000000')).toBeCloseTo(21, 5);
    expect(contrast('#777777', '#777777')).toBe(1);
  });

  it('is dark-only (one theme)', () => {
    expect(CSS).toMatch(/color-scheme:\s*dark/);
  });

  it('computes the hue', () => {
    expect(hue('#ff0000')).toBe(0);
    expect(hue('#00ff00')).toBe(120);
    expect(hue('#f5b301')).toBeCloseTo(43.8, 1);
  });

  it('--color-rating is amber (hue 35°–55°), distinct from the accent', () => {
    expect(hue(token('color-rating'))).toBeGreaterThanOrEqual(35);
    expect(hue(token('color-rating'))).toBeLessThanOrEqual(55);
    expect(token('color-rating').toLowerCase()).not.toBe(token('color-accent').toLowerCase());
  });

  it.each(['color-bg', 'color-surface', 'color-surface-2'])('stars (filled and empty outline) reach 3:1 on --%s', (bg) => {
    expect(contrast(token('color-rating'), token(bg))).toBeGreaterThanOrEqual(3);
    expect(contrast(token('color-text-muted'), token(bg))).toBeGreaterThanOrEqual(3);
  });
});
