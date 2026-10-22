// ISO 639-2 (B and T) → BCP 47, then Spanish names via Intl.DisplayNames(['es']).
import { S } from '@/strings.es';

const ISO_639_2_TO_BCP47: Record<string, string> = {
  eng: 'en', spa: 'es', fre: 'fr', fra: 'fr', ger: 'de', deu: 'de', ita: 'it', por: 'pt',
  dut: 'nl', nld: 'nl', jpn: 'ja', kor: 'ko', chi: 'zh', zho: 'zh', rus: 'ru', ara: 'ar',
  cat: 'ca', baq: 'eu', eus: 'eu', glg: 'gl', swe: 'sv', nor: 'no', nob: 'nb', nno: 'nn',
  dan: 'da', fin: 'fi', pol: 'pl', tur: 'tr', gre: 'el', ell: 'el', heb: 'he', hin: 'hi',
  hun: 'hu', cze: 'cs', ces: 'cs', slo: 'sk', slk: 'sk', rum: 'ro', ron: 'ro', ukr: 'uk',
  bul: 'bg', hrv: 'hr', srp: 'sr', slv: 'sl', tha: 'th', vie: 'vi', ind: 'id', may: 'ms',
  msa: 'ms', per: 'fa', fas: 'fa', ice: 'is', isl: 'is', lat: 'la', wel: 'cy', cym: 'cy',
  gle: 'ga', est: 'et', lav: 'lv', lit: 'lt', tam: 'ta', tel: 'te', ben: 'bn', urd: 'ur',
  fil: 'fil', tgl: 'tl',
};

let displayNames: Intl.DisplayNames | null | undefined;

function names(): Intl.DisplayNames | null {
  if (displayNames === undefined) {
    try {
      displayNames = new Intl.DisplayNames(['es'], { type: 'language', fallback: 'none' });
    } catch {
      displayNames = null;
    }
  }
  return displayNames;
}

/** BCP 47 code for an ISO 639-2 (or already 2-letter) code; null when unknown. */
export function toBcp47(code: string): string | null {
  const lower = code.trim().toLowerCase();
  if (ISO_639_2_TO_BCP47[lower]) return ISO_639_2_TO_BCP47[lower];
  return /^[a-z]{2}$/.test(lower) ? lower : null;
}

/** "Inglés", "Español", "Euskera"; `und` → "Desconocido"; unknown → the uppercase code. */
export function languageName(code: string): string {
  if (code.trim().toLowerCase() === 'und' || code.trim() === '') return S.common.unknown;
  const bcp47 = toBcp47(code);
  const name = bcp47 ? names()?.of(bcp47) : undefined;
  if (!name) return code.toUpperCase();
  return name.charAt(0).toLocaleUpperCase('es-ES') + name.slice(1);
}
