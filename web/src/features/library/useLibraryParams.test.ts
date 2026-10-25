import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_LIBRARY_PARAMS,
  hasFilters,
  isYearRangeInvalid,
  libraryParamsToSearch,
  parseLibraryParams,
  toLibraryQuery,
  useLibraryParams,
  type LibraryParams,
} from './useLibraryParams';

const parse = (search: string) => parseLibraryParams(new URLSearchParams(search));

describe('parseLibraryParams / libraryParamsToSearch', () => {
  it('defaults to sort=title&order=asc with no filters', () => {
    expect(parse('')).toEqual(DEFAULT_LIBRARY_PARAMS);
    expect(DEFAULT_LIBRARY_PARAMS).toEqual({ q: '', sort: 'title', order: 'asc', genre: null, yearFrom: null, yearTo: null, watched: null });
  });

  it('reads every param', () => {
    expect(parse('q=dune&sort=year&order=asc&genre=878&year_from=1990&year_to=2009&watched=false')).toEqual({
      q: 'dune',
      sort: 'year',
      order: 'asc',
      genre: 878,
      yearFrom: 1990,
      yearTo: 2009,
      watched: false,
    });
    expect(parse('watched=true').watched).toBe(true);
  });

  it('applies the default order of the sort when order is absent (title→asc, year→desc, added→desc)', () => {
    expect(parse('sort=year').order).toBe('desc');
    expect(parse('sort=added').order).toBe('desc');
    expect(parse('sort=title').order).toBe('asc');
  });

  it('ignores invalid values', () => {
    expect(parse('sort=rating&order=up&genre=abc&year_from=1800&year_to=20x9&q=%20%20&watched=yes')).toEqual(DEFAULT_LIBRARY_PARAMS);
    expect(parse('genre=-3&year_from=2101').genre).toBeNull();
  });

  it('trims q and caps it at 100 characters', () => {
    expect(parse('q=%20dune%20').q).toBe('dune');
    expect(parse(`q=${'a'.repeat(150)}`).q).toHaveLength(100);
  });

  it('writes only non-default values and round-trips', () => {
    expect(libraryParamsToSearch(DEFAULT_LIBRARY_PARAMS).toString()).toBe('');
    const p: LibraryParams = { q: 'blade runner', sort: 'year', order: 'asc', genre: 18, yearFrom: 1980, yearTo: null, watched: true };
    const search = libraryParamsToSearch(p);
    expect(search.toString()).toBe('q=blade+runner&sort=year&order=asc&genre=18&year_from=1980&watched=true');
    expect(parseLibraryParams(search)).toEqual(p);
    expect(libraryParamsToSearch({ ...DEFAULT_LIBRARY_PARAMS, sort: 'added', order: 'desc' }).toString()).toBe('sort=added');
  });
});

describe('filters and API query', () => {
  it('flags From > To as invalid', () => {
    expect(isYearRangeInvalid({ ...DEFAULT_LIBRARY_PARAMS, yearFrom: 2010, yearTo: 2000 })).toBe(true);
    expect(isYearRangeInvalid({ ...DEFAULT_LIBRARY_PARAMS, yearFrom: 2000, yearTo: 2000 })).toBe(false);
    expect(isYearRangeInvalid({ ...DEFAULT_LIBRARY_PARAMS, yearFrom: 2010 })).toBe(false);
  });

  it('knows when a filter is set (sort and order are not filters)', () => {
    expect(hasFilters({ ...DEFAULT_LIBRARY_PARAMS, sort: 'year', order: 'asc' })).toBe(false);
    expect(hasFilters({ ...DEFAULT_LIBRARY_PARAMS, q: 'x' })).toBe(true);
    expect(hasFilters({ ...DEFAULT_LIBRARY_PARAMS, genre: 18 })).toBe(true);
    expect(hasFilters({ ...DEFAULT_LIBRARY_PARAMS, yearTo: 2000 })).toBe(true);
    expect(hasFilters({ ...DEFAULT_LIBRARY_PARAMS, watched: false })).toBe(true);
  });

  it('maps to the API params, omitting empty values', () => {
    expect(toLibraryQuery(DEFAULT_LIBRARY_PARAMS)).toEqual({ sort: 'title', order: 'asc' });
    expect(
      toLibraryQuery({ q: 'dune', sort: 'added', order: 'desc', genre: 878, yearFrom: 1990, yearTo: 2009, watched: false }),
    ).toEqual({
      q: 'dune',
      sort: 'added',
      order: 'desc',
      genre: 878,
      year_from: 1990,
      year_to: 2009,
      watched: false,
    });
  });
});

describe('useLibraryParams (URL is the single source of truth)', () => {
  function setup(initial: string) {
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(
        MemoryRouter,
        { initialEntries: [initial], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
        children,
      );
    return renderHook(() => ({ lib: useLibraryParams(), location: useLocation() }), { wrapper });
  }

  it('reads the URL and updates it (replace) on change', () => {
    const { result } = setup('/films?genre=18');
    expect(result.current.lib.params.genre).toBe(18);
    expect(result.current.lib.hasFilters).toBe(true);

    act(() => result.current.lib.setSort('year'));
    expect(result.current.location.search).toBe('?sort=year&genre=18');
    expect(result.current.lib.params.order).toBe('desc');

    act(() => result.current.lib.toggleOrder());
    expect(result.current.location.search).toBe('?sort=year&order=asc&genre=18');

    act(() => result.current.lib.update({ yearFrom: 2010, yearTo: 2000 }));
    expect(result.current.lib.yearRangeInvalid).toBe(true);

    act(() => result.current.lib.clearFilters());
    expect(result.current.location.search).toBe('?sort=year&order=asc');
    expect(result.current.lib.hasFilters).toBe(false);
  });

  it('changing the sort resets the order to its default', () => {
    const { result } = setup('/films?sort=year&order=asc');
    act(() => result.current.lib.setSort('title'));
    expect(result.current.location.search).toBe('');
    expect(result.current.lib.params).toEqual(DEFAULT_LIBRARY_PARAMS);
  });
});
