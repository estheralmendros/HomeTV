import { describe, expect, it } from 'vitest';
import type { UnmatchedParsed } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { entryToDialogMode, fileStem, fixMatchRequest, parsedSummary, searchPrefill } from './entryToDialogMode';

const filmEntry = f.unmatchedEntry({
  id: 8,
  library: 'films',
  reason: 'no_tmdb_results',
  target: { type: 'film', id: 12, title: 'Dune Part 3', year: 2027 },
  parsed: { kind: 'film', title: 'Dune Part 3', year: 2027, part: null },
});
const showEntry = f.unmatchedEntry(); // low_confidence, target show 7 "The Office"
const fileEntry = f.unmatchedFileEntry(); // multi_part, media_file 91, films library

describe('entryToDialogMode', () => {
  it('a film target → film match mode → POST /api/admin/films/{id}/match {tmdb_id}', () => {
    const mode = entryToDialogMode(filmEntry);
    expect(mode).toEqual({ kind: 'film', id: 12, title: 'Dune Part 3', year: 2027 });
    expect(fixMatchRequest(mode, { tmdbId: 438631 })).toEqual({
      kind: 'matchFilm',
      path: '/api/admin/films/12/match',
      id: 12,
      body: { tmdb_id: 438631 },
    });
  });

  it('a show target → show match mode → POST /api/admin/shows/{id}/match {tmdb_id}', () => {
    const mode = entryToDialogMode(showEntry);
    expect(mode).toEqual({ kind: 'show', id: 7, title: 'The Office', year: null });
    expect(fixMatchRequest(mode, { tmdbId: 2316 })).toEqual({
      kind: 'matchShow',
      path: '/api/admin/shows/7/match',
      id: 7,
      body: { tmdb_id: 2316 },
    });
  });

  it('a media_file target → file (resolve) mode → POST /api/admin/unmatched/{entryId}/resolve', () => {
    const mode = entryToDialogMode(fileEntry);
    expect(mode).toEqual({
      kind: 'file',
      entryId: 6,
      library: 'films',
      reason: 'multi_part',
      parsed: { kind: 'film', title: 'Kill Bill Vol 1', year: 2003, part: 1 },
      relative_path: 'Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi',
    });
    expect(fixMatchRequest(mode, { tmdbId: 24 })).toEqual({
      kind: 'resolve',
      path: '/api/admin/unmatched/6/resolve',
      entryId: 6,
      body: { tmdb_id: 24 },
    });
  });

  it('a shows-library file entry resolves with season_number and sorted unique episode_numbers', () => {
    const mode = entryToDialogMode(
      f.unmatchedFileEntry({
        id: 9,
        library: 'shows',
        reason: 'no_season',
        target: { type: 'media_file', id: 501, relative_path: 'One Piece/One Piece - 105.mkv' },
        parsed: null,
      }),
    );
    expect(fixMatchRequest(mode, { tmdbId: 37854, seasonNumber: 5, episodeNumbers: [106, 105, 105] })).toEqual({
      kind: 'resolve',
      path: '/api/admin/unmatched/9/resolve',
      entryId: 9,
      body: { tmdb_id: 37854, season_number: 5, episode_numbers: [105, 106] },
    });
  });

  it('the resolve endpoint is never used for film/show targets', () => {
    for (const entry of [filmEntry, showEntry]) {
      expect(fixMatchRequest(entryToDialogMode(entry), { tmdbId: 1 }).path).not.toMatch(/resolve/);
    }
  });
});

describe('parsedSummary', () => {
  it.each([
    ['episode, no year', { kind: 'episode', show_title: 'The Office', show_year: null, season: 2, episodes: [3] }, 'The Office · T2 · E3'],
    ['episode with year', { kind: 'episode', show_title: 'The Office', show_year: 2005, season: 2, episodes: [3] }, 'The Office (2005) · T2 · E3'],
    ['multi-episode', { kind: 'episode', show_title: 'Friends', show_year: null, season: 1, episodes: [2, 1] }, 'Friends · T1 · E1–E2'],
    ['film with part', { kind: 'film', title: 'Kill Bill Vol 1', year: 2003, part: 1 }, 'Kill Bill Vol 1 (2003) · Parte 1'],
    ['film without part', { kind: 'film', title: 'Dune', year: 2021, part: null }, 'Dune (2021)'],
    ['film without year', { kind: 'film', title: 'Dune', year: null, part: null }, 'Dune'],
  ] satisfies Array<[string, UnmatchedParsed, string]>)('%s', (_name, parsed, expected) => {
    expect(parsedSummary(parsed, 'whatever/file.mkv')).toBe(expected);
  });

  it('null → the file-name stem of relative_path (no Part suffix)', () => {
    expect(parsedSummary(null, 'One Piece/One Piece - 105.mkv')).toBe('One Piece - 105');
    expect(parsedSummary(null, 'Kill Bill/Kill.Bill.Vol.1.2003.cd1.avi')).toBe('Kill.Bill.Vol.1.2003.cd1');
  });
});

describe('fileStem', () => {
  it.each([
    ['Broken/broken.mkv', 'broken'],
    ['movie', 'movie'],
    ['a/b/.hidden', '.hidden'],
    ['Show/S01/ep.part.mp4', 'ep.part'],
  ])('%s → %s', (path, stem) => {
    expect(fileStem(path)).toBe(stem);
  });
});

describe('searchPrefill', () => {
  it('film/show modes use the item title and year', () => {
    expect(searchPrefill(entryToDialogMode(filmEntry))).toEqual({ type: 'movie', q: 'Dune Part 3', year: '2027' });
    expect(searchPrefill(entryToDialogMode(showEntry))).toEqual({ type: 'tv', q: 'The Office', year: '' });
  });

  it('file mode uses parsed.title / parsed.show_title and their years, else the file stem', () => {
    expect(searchPrefill(entryToDialogMode(fileEntry))).toEqual({ type: 'movie', q: 'Kill Bill Vol 1', year: '2003' });
    expect(
      searchPrefill(
        entryToDialogMode(
          f.unmatchedFileEntry({
            library: 'shows',
            parsed: { kind: 'episode', show_title: 'The Office', show_year: 2005, season: 2, episodes: [3] },
          }),
        ),
      ),
    ).toEqual({ type: 'tv', q: 'The Office', year: '2005' });
    expect(
      searchPrefill(
        entryToDialogMode(
          f.unmatchedFileEntry({
            library: 'shows',
            parsed: null,
            target: { type: 'media_file', id: 1, relative_path: 'One Piece/One Piece - 105.mkv' },
          }),
        ),
      ),
    ).toEqual({ type: 'tv', q: 'One Piece - 105', year: '' });
  });
});
