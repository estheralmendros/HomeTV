// Pure helpers of the Unmatched page and the fix-match dialog (prd2 §11): row → dialog mode,
// the parsed summary, the search prefill and the apply request chosen by mode.
import type { ResolveRequest, TmdbType, UnmatchedEntry, UnmatchedParsed } from '@/api/types';
import { episodeCode } from '@/lib/format';
import { S } from '@/strings.es';
import type { FixMatchMode } from './fixMatchTypes';

/**
 * film/show targets are fixed with the match endpoints, media_file targets with resolve (which
 * would answer 409 not_a_file_entry for a film/show entry).
 */
export function entryToDialogMode(entry: UnmatchedEntry): FixMatchMode {
  const { target } = entry;
  if (target.type === 'media_file') {
    return {
      kind: 'file',
      entryId: entry.id,
      library: entry.library,
      reason: entry.reason,
      parsed: entry.parsed,
      relative_path: target.relative_path,
    };
  }
  return { kind: target.type, id: target.id, title: target.title, year: target.year };
}

/** File name of a relative path without its extension ("One Piece/One Piece - 105.mkv" → "One Piece - 105"). */
export function fileStem(relativePath: string): string {
  const name = relativePath.split('/').pop() ?? relativePath;
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}

/** "The Office (2005) · T2 · E3", "Kill Bill Vol 1 (2003) · Parte 1", or the file stem for null. */
export function parsedSummary(parsed: UnmatchedParsed | null, relativePath: string): string {
  if (parsed === null) return fileStem(relativePath);
  if (parsed.kind === 'episode') {
    const episodes = [...parsed.episodes].sort((a, b) => a - b);
    const name = S.common.titleWithYear(parsed.show_title, parsed.show_year);
    const first = episodes[0];
    if (first === undefined) return `${name} · ${S.show.seasonName(parsed.season)}`;
    return `${name} · ${episodeCode(parsed.season, first, episodes[episodes.length - 1])}`;
  }
  const name = S.common.titleWithYear(parsed.title, parsed.year);
  return parsed.part === null ? name : `${name} · ${S.versions.part(parsed.part)}`;
}

export interface SearchPrefill {
  type: TmdbType;
  q: string;
  /** '' when unknown (the request then omits `year`). */
  year: string;
}

/** The dialog's initial search: type from the kind/library, query and year from the title or `parsed`. */
export function searchPrefill(mode: FixMatchMode): SearchPrefill {
  const yearText = (year: number | null | undefined) => (year == null ? '' : String(year));
  if (mode.kind !== 'file') {
    return { type: mode.kind === 'film' ? 'movie' : 'tv', q: mode.title, year: yearText(mode.year) };
  }
  const type: TmdbType = mode.library === 'films' ? 'movie' : 'tv';
  const { parsed } = mode;
  if (parsed === null) return { type, q: fileStem(mode.relative_path), year: '' };
  if (parsed.kind === 'film') return { type, q: parsed.title, year: yearText(parsed.year) };
  return { type, q: parsed.show_title, year: yearText(parsed.show_year) };
}

export interface FixMatchSelection {
  tmdbId: number;
  /** Shows-library file entries only. */
  seasonNumber?: number;
  episodeNumbers?: number[];
}

export type FixMatchRequest =
  | { kind: 'matchFilm'; path: string; id: number; body: { tmdb_id: number } }
  | { kind: 'matchShow'; path: string; id: number; body: { tmdb_id: number } }
  | { kind: 'resolve'; path: string; entryId: number; body: ResolveRequest };

/** The one request "Aplicar" sends (prd2 §11 step 4 table). */
export function fixMatchRequest(mode: FixMatchMode, selection: FixMatchSelection): FixMatchRequest {
  const tmdbId = selection.tmdbId;
  if (mode.kind !== 'file') {
    return mode.kind === 'film'
      ? { kind: 'matchFilm', path: `/api/admin/films/${mode.id}/match`, id: mode.id, body: { tmdb_id: tmdbId } }
      : { kind: 'matchShow', path: `/api/admin/shows/${mode.id}/match`, id: mode.id, body: { tmdb_id: tmdbId } };
  }
  const body: ResolveRequest = { tmdb_id: tmdbId };
  if (mode.library === 'shows') {
    body.season_number = selection.seasonNumber;
    body.episode_numbers = [...new Set(selection.episodeNumbers ?? [])].sort((a, b) => a - b);
  }
  return { kind: 'resolve', path: `/api/admin/unmatched/${mode.entryId}/resolve`, entryId: mode.entryId, body };
}
