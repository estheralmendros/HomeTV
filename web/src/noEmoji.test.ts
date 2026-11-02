import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { PLAY_GLYPH, WARNING_GLYPH } from '@/components/glyphs';

// Icons: icons are monochrome glyphs or SVG in currentColor; a colour emoji never
// reaches the UI. That covers Emoji_Presentation characters (🔊 🔇) and every other Emoji=Yes
// character (▶ ⚠, which iPadOS may draw from Apple Color Emoji) unless the text-presentation
// selector U+FE0E follows it. ASCII digits, # and * are Emoji=Yes too (keycap bases) and allowed.
const EMOJI = /\p{Emoji_Presentation}|(?![#*0-9])\p{Emoji}(?!\uFE0E)/gu;
const VS15 = String.fromCodePoint(0xfe0e); // text presentation
const VS16 = String.fromCodePoint(0xfe0f); // emoji presentation

function emojiIn(text: string): string[] {
  return [...text.matchAll(EMOJI)].map((m) => m[0]);
}

function uiSources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return uiSources(path);
    const ui = /\.tsx?$/.test(path) && !/\.test\.tsx?$/.test(path);
    return ui ? [path] : [];
  });
}

const SRC = join(__dirname);

describe('no colour emoji in the UI', () => {
  it('detects emoji-presentation and emoji-capable characters without U+FE0E', () => {
    expect(emojiIn('🔊 🔇')).toEqual(['🔊', '🔇']);
    expect(emojiIn('▶ ⚠')).toEqual(['▶', '⚠']);
    expect(emojiIn(`▶${VS16}`)).toEqual(['▶']);
    expect(emojiIn(`▶${VS15} ⚠${VS15}`)).toEqual([]);
    expect(emojiIn('T2 · E3 #1 * ✓ ‹ › ⌫ ❚❚ ⛶ ♪ ∓')).toEqual([]);
  });

  it('the shared glyphs carry the text-presentation selector', () => {
    expect([...PLAY_GLYPH].map((c) => c.codePointAt(0))).toEqual([0x25b6, 0xfe0e]);
    expect([...WARNING_GLYPH].map((c) => c.codePointAt(0))).toEqual([0x26a0, 0xfe0e]);
    expect(emojiIn(PLAY_GLYPH + WARNING_GLYPH)).toEqual([]);
  });

  it('no src/**/*.ts(x) source (string catalog included) contains a colour-emoji character', () => {
    const found = uiSources(SRC).flatMap((path) => emojiIn(readFileSync(path, 'utf8')).map((m) => `${relative(SRC, path)}: ${m}`));
    expect(found).toEqual([]);
  });
});
