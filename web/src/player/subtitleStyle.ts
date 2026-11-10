// The subtitle style (prd.md FR-6) for the web renderer, pure: the `::cue` rule, the auto-cue line
// per position, the clash rule and the panel's keyboard steps.
import { DEFAULT_SUBTITLE_STYLE } from '@/api/subtitleStyleDefaults';
import type { Me, SubtitleColor, SubtitleEdgeColor, SubtitlePosition, SubtitleSize, SubtitleStyle } from '@/api/types';

export { DEFAULT_SUBTITLE_STYLE };

export type StyleRow = 'size' | 'color' | 'edge' | 'edge_color' | 'position';

/** A row's value type (e.g. `SubtitleColor` for 'color'). */
export type StyleValue<R extends StyleRow> = SubtitleStyle[R];

/** Every row's values in panel order. */
export const STYLE_VALUES: { readonly [R in StyleRow]: readonly StyleValue<R>[] } = {
  size: ['small', 'medium', 'large'],
  color: ['white', 'yellow', 'green', 'cyan', 'magenta', 'orange'],
  edge: ['outline', 'shadow', 'none'],
  edge_color: ['black', 'dark_gray', 'white'],
  position: ['low', 'middle', 'high'],
};

export const STYLE_ROWS: readonly StyleRow[] = ['size', 'color', 'edge', 'edge_color', 'position'];

export const COLOR_HEX: Record<SubtitleColor, string> = {
  white: '#FFFFFF',
  yellow: '#FFFF00',
  green: '#00FF00',
  cyan: '#00FFFF',
  magenta: '#FF00FF',
  orange: '#FFA500',
};

export const EDGE_COLOR_HEX: Record<SubtitleEdgeColor, string> = {
  black: '#000000',
  dark_gray: '#404040',
  white: '#FFFFFF',
};

/** The text size factor (`::cue` font-size and the sample line). */
export const SIZE_FACTOR: Record<SubtitleSize, number> = { small: 0.8, medium: 1, large: 1.3 };

function knownValue<R extends StyleRow>(row: R, value: unknown): StyleValue<R> {
  const values: readonly unknown[] = STYLE_VALUES[row];
  return values.includes(value) ? (value as StyleValue<R>) : DEFAULT_SUBTITLE_STYLE[row];
}

/**
 * The profile's style; the default for a bare Profile (seeded before /api/me answered) or none.
 * Each field is checked at runtime: an unknown value falls back to that field's default (like the
 * TV's `SubtitleLook.of`), so a newer server value never draws `undefined`.
 */
export function subtitleStyleOf(me: Partial<Me> | undefined | null): SubtitleStyle {
  const stored = me?.subtitle_style;
  if (!stored) return DEFAULT_SUBTITLE_STYLE;
  return {
    size: knownValue('size', stored.size),
    color: knownValue('color', stored.color),
    edge: knownValue('edge', stored.edge),
    edge_color: knownValue('edge_color', stored.edge_color),
    position: knownValue('position', stored.position),
  };
}

/** A stored white-on-white clash (edge on) is drawn with black. */
export function effectiveEdgeColor(style: SubtitleStyle): SubtitleEdgeColor {
  return style.color === 'white' && style.edge_color === 'white' && style.edge !== 'none' ? 'black' : style.edge_color;
}

const OUTLINE_OFFSETS = [
  ['0.04em', '0'],
  ['-0.04em', '0'],
  ['0', '0.04em'],
  ['0', '-0.04em'],
  ['0.04em', '0.04em'],
  ['-0.04em', '0.04em'],
  ['0.04em', '-0.04em'],
  ['-0.04em', '-0.04em'],
] as const;

export function textShadow(style: SubtitleStyle): string {
  const edge = EDGE_COLOR_HEX[effectiveEdgeColor(style)];
  if (style.edge === 'outline') return OUTLINE_OFFSETS.map(([x, y]) => `${x} ${y} 0 ${edge}`).join(', ');
  if (style.edge === 'shadow') return `0.07em 0.07em 0.07em ${edge}`;
  return 'none';
}

/** The one generated rule: colour, size, edge and no background box. */
export function cueCss(style: SubtitleStyle, selector: string): string {
  const size = `${Math.round(SIZE_FACTOR[style.size] * 100)}%`;
  return `${selector}::cue { color: ${COLOR_HEX[style.color]}; font-size: ${size}; text-shadow: ${textShadow(style)}; background: transparent; }`;
}

const CUE_LINES: Record<SubtitlePosition, number> = { low: -3, middle: -4, high: -5 };

/** The line of auto-positioned cues (counted from the bottom, `snapToLines`). */
export function cueLine(position: SubtitlePosition): number {
  return CUE_LINES[position];
}

/** Clash values and the edge colours while "Sin contorno" is chosen. */
export function isDisabled<R extends StyleRow>(style: SubtitleStyle, row: R, value: StyleValue<R>): boolean {
  if (row === 'edge_color') return style.edge === 'none' || (value === 'white' && style.color === 'white');
  if (row === 'color') return value === 'white' && style.edge_color === 'white' && style.edge !== 'none';
  return false;
}

/** Sets `row` to `value` (a disabled value changes nothing); an edge turned on again never clashes. */
export function choose<R extends StyleRow>(style: SubtitleStyle, row: R, value: StyleValue<R>): SubtitleStyle {
  if (!STYLE_VALUES[row].includes(value) || isDisabled(style, row, value)) return style;
  const next = { ...style, [row]: value } as SubtitleStyle;
  if (row === 'edge' && style.edge === 'none' && next.edge !== 'none' && next.color === 'white' && next.edge_color === 'white') {
    next.edge_color = 'black';
  }
  return next;
}

/** The next enabled value in `dir` (disabled ones skipped); unchanged at the ends. */
export function step<R extends StyleRow>(style: SubtitleStyle, row: R, dir: 1 | -1): SubtitleStyle {
  const values = STYLE_VALUES[row];
  for (let i = values.indexOf(style[row]) + dir; i >= 0 && i < values.length; i += dir) {
    const value = values[i];
    if (value !== undefined && !isDisabled(style, row, value)) return choose(style, row, value);
  }
  return style;
}

/** The first (or last) enabled value of a row (Home / End). */
export function edge<R extends StyleRow>(style: SubtitleStyle, row: R, which: 'first' | 'last'): SubtitleStyle {
  const values: readonly StyleValue<R>[] = STYLE_VALUES[row];
  const ordered = which === 'first' ? values : [...values].reverse();
  const value = ordered.find((v) => !isDisabled(style, row, v));
  return value === undefined ? style : choose(style, row, value);
}
