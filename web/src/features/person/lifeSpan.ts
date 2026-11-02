// Pure date/age helpers of the actor screen. Dates are TMDB
// "YYYY-MM-DD" strings; ages are computed on integers, never with Date arithmetic or time zones.
import { LOCALE } from '@/lib/intl';
import { S } from '@/strings.es';

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

const longDate = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });

function parts(iso: string): [number, number, number] | null {
  const m = ISO_DATE.exec(iso);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  // Calendar check via a UTC round trip: "2021-02-30" would otherwise roll over to 2 March.
  const date = new Date(Date.UTC(y, mo - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== mo - 1 || date.getUTCDate() !== d) return null;
  return [y, mo, d];
}

/** A real calendar date written as "YYYY-MM-DD". */
export function isValidIsoDate(iso: string): boolean {
  return parts(iso) !== null;
}

/** "8 de octubre de 1992". Date-only, formatted in UTC so it never shifts a day. Valid dates only. */
export function formatLongDate(iso: string): string {
  return longDate.format(new Date(`${iso}T00:00:00Z`));
}

/** Completed years from `birth` to `on` (both "YYYY-MM-DD"); null when either is invalid or the result < 0. */
export function ageOn(birth: string, on: string): number | null {
  const b = parts(birth);
  const o = parts(on);
  if (!b || !o) return null;
  const [y1, m1, d1] = b;
  const [y2, m2, d2] = o;
  const age = y2 - y1 - (m2 < m1 || (m2 === m1 && d2 < d1) ? 1 : 0);
  return age < 0 ? null : age;
}

const pad = (n: number) => String(n).padStart(2, '0');

/** Local calendar date of `now` as "YYYY-MM-DD" (the viewer's day). */
export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** The life span line or null; invalid dates count as unknown. */
export function lifeSpanText(birthday: string | null, deathday: string | null, today: string): string | null {
  const birth = birthday !== null && isValidIsoDate(birthday) ? birthday : null;
  const death = deathday !== null && isValidIsoDate(deathday) ? deathday : null;
  if (birth !== null && death !== null) {
    const age = ageOn(birth, death);
    return age === null
      ? S.person.lifeDeceasedNoAge(formatLongDate(birth), formatLongDate(death))
      : S.person.lifeDeceased(formatLongDate(birth), formatLongDate(death), age);
  }
  if (birth !== null) {
    const age = ageOn(birth, today);
    return age === null ? formatLongDate(birth) : S.person.lifeLiving(formatLongDate(birth), age);
  }
  if (death !== null) return S.person.deathOnly(formatLongDate(death));
  return null;
}
