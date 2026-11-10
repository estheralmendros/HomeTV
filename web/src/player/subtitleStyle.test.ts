import { describe, expect, it } from 'vitest';
import type { SubtitleStyle } from '@/api/types';
import * as f from '../../tests/fixtures';
import {
  COLOR_HEX,
  DEFAULT_SUBTITLE_STYLE,
  EDGE_COLOR_HEX,
  choose,
  cueCss,
  cueLine,
  effectiveEdgeColor,
  isDisabled,
  step,
  subtitleStyleOf,
  textShadow,
} from './subtitleStyle';
import { SUBTITLE_CUE_LINE } from './tracks';

const style = (overrides: Partial<SubtitleStyle> = {}) => f.subtitleStyle(overrides);
const OUTLINE_BLACK = [
  '0.04em 0 0 #000000',
  '-0.04em 0 0 #000000',
  '0 0.04em 0 #000000',
  '0 -0.04em 0 #000000',
  '0.04em 0.04em 0 #000000',
  '-0.04em 0.04em 0 #000000',
  '0.04em -0.04em 0 #000000',
  '-0.04em -0.04em 0 #000000',
].join(', ');

describe('values (prd.md FR-6)', () => {
  it('default = medium / white / outline / black / low', () => {
    expect(DEFAULT_SUBTITLE_STYLE).toEqual({ size: 'medium', color: 'white', edge: 'outline', edge_color: 'black', position: 'low' });
  });

  it('colour hex values', () => {
    expect(COLOR_HEX).toEqual({
      white: '#FFFFFF',
      yellow: '#FFFF00',
      green: '#00FF00',
      cyan: '#00FFFF',
      magenta: '#FF00FF',
      orange: '#FFA500',
    });
    expect(EDGE_COLOR_HEX).toEqual({ black: '#000000', dark_gray: '#404040', white: '#FFFFFF' });
  });

  it('subtitleStyleOf: the profile style, else the default (a bare Profile, undefined)', () => {
    expect(subtitleStyleOf(f.me({ subtitle_style: style({ color: 'cyan' }) })).color).toBe('cyan');
    expect(subtitleStyleOf(f.profile())).toEqual(DEFAULT_SUBTITLE_STYLE);
    expect(subtitleStyleOf(undefined)).toEqual(DEFAULT_SUBTITLE_STYLE);
  });

  it('subtitleStyleOf: an unknown or missing value falls back to that field’s default only', () => {
    const odd = { size: 'huge', color: 'cyan', edge: 42, edge_color: null, position: 'high' } as unknown as SubtitleStyle;
    expect(subtitleStyleOf(f.me({ subtitle_style: odd }))).toEqual(style({ color: 'cyan', position: 'high' }));
    const partial = { color: 'yellow' } as unknown as SubtitleStyle;
    expect(subtitleStyleOf(f.me({ subtitle_style: partial }))).toEqual(style({ color: 'yellow' }));
    expect(subtitleStyleOf(f.me({ subtitle_style: null as unknown as SubtitleStyle }))).toEqual(DEFAULT_SUBTITLE_STYLE);
  });

  it('cueLine: low -3 (SUBTITLE_CUE_LINE), middle -4, high -5', () => {
    expect([cueLine('low'), cueLine('middle'), cueLine('high')]).toEqual([-3, -4, -5]);
    expect(SUBTITLE_CUE_LINE).toBe(cueLine('low'));
  });
});

describe('text-shadow and the ::cue rule', () => {
  it('outline: 8 offsets of 0.04em, blur 0, in the edge colour', () => {
    expect(textShadow(style())).toBe(OUTLINE_BLACK);
    expect(textShadow(style({ edge_color: 'dark_gray' }))).toContain('0.04em 0 0 #404040');
  });

  it('shadow: 0.07em down-right with a 0.07em blur; none: none', () => {
    expect(textShadow(style({ edge: 'shadow', edge_color: 'white', color: 'yellow' }))).toBe('0.07em 0.07em 0.07em #FFFFFF');
    expect(textShadow(style({ edge: 'none' }))).toBe('none');
  });

  it('a stored white-on-white clash is drawn with a black edge', () => {
    const clash = style({ color: 'white', edge_color: 'white' });
    expect(effectiveEdgeColor(clash)).toBe('black');
    expect(textShadow(clash)).toBe(OUTLINE_BLACK);
    expect(effectiveEdgeColor(style({ color: 'yellow', edge_color: 'white' }))).toBe('white');
  });

  it.each([
    ['small', '80%'],
    ['medium', '100%'],
    ['large', '130%'],
  ] as const)('size %s → font-size %s', (size, percent) => {
    expect(cueCss(style({ size }), '.video')).toContain(`font-size: ${percent}`);
  });

  it.each(Object.entries(COLOR_HEX))('colour %s → %s', (color, hex) => {
    expect(cueCss(style({ color: color as SubtitleStyle['color'] }), '.video')).toContain(`color: ${hex}`);
  });

  it('one rule for the selector with a transparent background', () => {
    expect(cueCss(style({ edge: 'none' }), '.video')).toBe(
      '.video::cue { color: #FFFFFF; font-size: 100%; text-shadow: none; background: transparent; }',
    );
  });
});

describe('clash rule and keyboard steps', () => {
  it('white edge colour is disabled with white text; white text is disabled with a white edge (edge on)', () => {
    expect(isDisabled(style(), 'edge_color', 'white')).toBe(true);
    expect(isDisabled(style({ color: 'yellow' }), 'edge_color', 'white')).toBe(false);
    expect(isDisabled(style({ color: 'yellow', edge_color: 'white' }), 'color', 'white')).toBe(true);
    expect(isDisabled(style({ color: 'yellow', edge_color: 'white', edge: 'none' }), 'color', 'white')).toBe(false);
  });

  it('every edge colour is disabled with "Sin contorno"', () => {
    for (const value of ['black', 'dark_gray', 'white'] as const) expect(isDisabled(style({ edge: 'none' }), 'edge_color', value)).toBe(true);
    expect(step(style({ edge: 'none' }), 'edge_color', 1)).toEqual(style({ edge: 'none' }));
  });

  it('step moves to the next enabled value and stops at the ends', () => {
    expect(step(style(), 'size', 1).size).toBe('large');
    expect(step(style({ size: 'large' }), 'size', 1).size).toBe('large');
    expect(step(style({ size: 'small' }), 'size', -1).size).toBe('small');
    expect(step(style(), 'position', 1).position).toBe('middle');
  });

  it('step skips the disabled clash value', () => {
    // dark_gray → (white disabled) → stays at dark_gray
    expect(step(style({ edge_color: 'dark_gray' }), 'edge_color', 1).edge_color).toBe('dark_gray');
    // yellow ← (white disabled with a white edge): stays yellow
    const whiteEdge = style({ color: 'yellow', edge_color: 'white' });
    expect(step(whiteEdge, 'color', -1).color).toBe('yellow');
    expect(step(style({ color: 'yellow', edge_color: 'white' }), 'color', 1).color).toBe('green');
  });

  it('choose ignores a disabled value', () => {
    expect(choose(style(), 'edge_color', 'white')).toEqual(style());
  });

  it('turning the edge back on with white text and a white stored edge colour gives black', () => {
    const off = style({ color: 'yellow', edge_color: 'white', edge: 'none' });
    const whiteText = choose(off, 'color', 'white');
    expect(whiteText).toMatchObject({ color: 'white', edge_color: 'white', edge: 'none' });
    expect(choose(whiteText, 'edge', 'shadow')).toMatchObject({ edge: 'shadow', edge_color: 'black' });
    expect(step(whiteText, 'edge', -1)).toMatchObject({ edge: 'shadow', edge_color: 'black' });
  });
});
