// es-ES formatting helpers (prd2 UI language, Technical Requirements). Intl objects are
// created once and reused.
import { S } from '@/strings.es';
import { countFormat, LOCALE, plural } from './intl';

const dateFormat = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short', year: 'numeric' });
const dateOnlyFormat = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const relativeFormat = new Intl.RelativeTimeFormat(LOCALE, { numeric: 'auto' });
const ratingFormat = new Intl.NumberFormat(LOCALE, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const decimalFormat = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1, useGrouping: 'always' });

const pad2 = (n: number) => String(n).padStart(2, '0');

function wholeSeconds(seconds: number): number {
  return Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
}

/** Clock time: "1:12:05" / "12:31" ("0:12:31" with `withHours`). */
export function formatDuration(seconds: number, { withHours = false }: { withHours?: boolean } = {}): string {
  const total = wholeSeconds(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 || withHours ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`;
}

/** Spoken duration for aria-valuetext: "12 minutos y 31 segundos". */
export function formatSpokenDuration(seconds: number): string {
  const total = wholeSeconds(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const parts = [
    h > 0 ? plural(h, 'hora', 'horas') : null,
    m > 0 ? plural(m, 'minuto', 'minutos') : null,
    s > 0 ? plural(s, 'segundo', 'segundos') : null,
  ].filter((p): p is string => p !== null);
  if (parts.length === 0) return plural(0, 'segundo', 'segundos');
  if (parts.length === 1) return parts[0]!;
  return `${parts.slice(0, -1).join(', ')} y ${parts.at(-1)!}`;
}

/** Runtime in minutes: "2 h 35 min", "45 min", "2 h". */
export function formatRuntime(minutes: number): string {
  const total = Math.max(0, Math.round(minutes));
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** "4 oct 2005". Date-only values are formatted in UTC so they never shift a day. */
export function formatDate(iso: string): string {
  const format = DATE_ONLY.test(iso) ? dateOnlyFormat : dateFormat;
  return format.format(new Date(DATE_ONLY.test(iso) ? `${iso}T00:00:00Z` : iso)).replace(/\./g, '');
}

const RELATIVE_UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
  ['second', 1],
];

/** "hace 2 minutos" (Intl.RelativeTimeFormat es-ES, numeric auto). */
export function formatRelative(iso: string, now: number = Date.now()): string {
  const diffSeconds = Math.round((Date.parse(iso) - now) / 1000);
  const abs = Math.abs(diffSeconds);
  for (const [unit, size] of RELATIVE_UNITS) {
    if (abs >= size || unit === 'second') {
      return relativeFormat.format(Math.trunc(diffSeconds / size), unit);
    }
  }
  return relativeFormat.format(0, 'second');
}

/** "1.234" (grouping always on, also for 4-digit numbers). */
export function formatCount(n: number): string {
  return countFormat.format(n);
}

/** "7,8" */
export function formatRating(value: number): string {
  return ratingFormat.format(value);
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

/** Decimal units with a decimal comma: "58,1 GB". */
export function formatBytes(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= 1000 && unit < BYTE_UNITS.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${decimalFormat.format(value)} ${BYTE_UNITS[unit]}`;
}

/** "T2 · E3", or "T2 · E3–E4" for a multi-episode file. Never "S02E03". */
export function episodeCode(season: number, episode: number, lastEpisode?: number): string {
  const range = lastEpisode !== undefined && lastEpisode !== episode ? `E${episode}–E${lastEpisode}` : `E${episode}`;
  return `T${season} · ${range}`;
}

/** Episode rows: "E3". */
export function episodeShort(episode: number): string {
  return `E${episode}`;
}

/** Season name from the API, else "Temporada N" ("Especiales" for 0). */
export function seasonLabel(seasonNumber: number, name: string | null | undefined): string {
  return name?.trim() ? name : S.show.seasonName(seasonNumber);
}

/** "16-17, 3" → [3, 16, 17] (sorted, unique, ≥ 1). Throws on invalid input. */
export function parseEpisodeList(input: string): number[] {
  const parts = input.split(',').map((p) => p.trim());
  if (parts.length === 0 || parts.some((p) => p === '')) throw new Error(`invalid episode list: ${input}`);
  const numbers = new Set<number>();
  for (const part of parts) {
    const match = /^(\d+)\s*(?:[-–]\s*(\d+))?$/.exec(part);
    if (!match) throw new Error(`invalid episode list: ${input}`);
    const from = Number(match[1]);
    const to = match[2] === undefined ? from : Number(match[2]);
    if (from < 1 || to < from || to - from > 500) throw new Error(`invalid episode list: ${input}`);
    for (let n = from; n <= to; n += 1) numbers.add(n);
  }
  return [...numbers].sort((a, b) => a - b);
}
