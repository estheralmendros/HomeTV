// es-ES Intl instances, created once and shared (UI language, Rev 9).
export const LOCALE = 'es-ES';

export const countFormat = new Intl.NumberFormat(LOCALE, { useGrouping: 'always' });
export const pluralRules = new Intl.PluralRules(LOCALE);

/** "1 episodio" / "1.234 episodios" (Intl.PluralRules es-ES). */
export function plural(n: number, one: string, other: string): string {
  return `${countFormat.format(n)} ${pluralRules.select(n) === 'one' ? one : other}`;
}
