// es-ES Intl instances, created once and shared.
export const LOCALE = 'es-ES';

export const countFormat = new Intl.NumberFormat(LOCALE, { useGrouping: 'always' });
export const pluralRules = new Intl.PluralRules(LOCALE);

/** "1 episodio" / "1.234 episodios" (Intl.PluralRules es-ES). */
export function plural(n: number, one: string, other: string): string {
  return `${countFormat.format(n)} ${pluralRules.select(n) === 'one' ? one : other}`;
}

/** Star values: "4,5", "4", "0,5" (at most one decimal, decimal comma). */
export const starsFormat = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1 });
/** Exactly one decimal, "4,0" / "7,8": the household average and the TMDB rating. */
export const oneDecimalFormat = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
