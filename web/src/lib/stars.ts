// Pure star helpers: fills, keyboard steps, pointer halves. Values are
// ½ … 5 in ½ steps; null = not rated. The text equivalent lives in S.ratings.stars.
export type StarFill = 'full' | 'half' | 'empty';

export const MIN_STARS = 0.5;
export const MAX_STARS = 5;

/** Five fills for `value` (null = five empty stars). */
export function starFills(value: number | null): StarFill[] {
  const v = value ?? 0;
  return [0, 1, 2, 3, 4].map((i) => (v >= i + 1 ? 'full' : v >= i + 0.5 ? 'half' : 'empty'));
}

/** One ½ step up or down, clamped to ½ … 5; from empty both directions give ½. */
export function stepStars(value: number | null, delta: 1 | -1): number {
  if (value === null) return MIN_STARS;
  return Math.min(MAX_STARS, Math.max(MIN_STARS, value + delta * 0.5));
}

/** Star `index` (0-based) hit at `fraction` of its width: left half → index + ½, right half → index + 1. */
export function starsFromPointer(index: number, fraction: number): number {
  return fraction < 0.5 ? index + 0.5 : index + 1;
}

/** Average icons: the nearest ½ (the text keeps one decimal). */
export function roundToHalf(value: number): number {
  return Math.round(value * 2) / 2;
}
