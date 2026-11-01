import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { MemoryRouter, useLocation, useNavigate, useNavigationType } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { parseWatchlistParams, toWatchlistQuery, useWatchlistParams, watchlistParamsToSearch } from './useWatchlistParams';

const parse = (search: string) => parseWatchlistParams(new URLSearchParams(search));

describe('parseWatchlistParams / watchlistParamsToSearch', () => {
  it.each<[string, { type: string; sort: string; watched: boolean | null }]>([
    ['', { type: 'all', sort: 'added', watched: null }],
    ['type=films', { type: 'films', sort: 'added', watched: null }],
    ['type=shows&sort=title', { type: 'shows', sort: 'title', watched: null }],
    ['sort=year', { type: 'all', sort: 'year', watched: null }],
    ['type=all&sort=added', { type: 'all', sort: 'added', watched: null }],
    ['type=x&sort=y', { type: 'all', sort: 'added', watched: null }],
    ['type=FILMS&sort=Title', { type: 'all', sort: 'added', watched: null }],
    ['watched=true', { type: 'all', sort: 'added', watched: true }],
    ['type=films&watched=false&sort=year', { type: 'films', sort: 'year', watched: false }],
    ['watched=1', { type: 'all', sort: 'added', watched: null }],
    ['watched=TRUE', { type: 'all', sort: 'added', watched: null }],
    ['watched=', { type: 'all', sort: 'added', watched: null }],
  ])('"%s"', (search, expected) => {
    expect(parse(search)).toEqual(expected);
  });

  it('writes only non-default values', () => {
    expect(watchlistParamsToSearch({ type: 'all', sort: 'added', watched: null }).toString()).toBe('');
    expect(watchlistParamsToSearch({ type: 'films', sort: 'added', watched: null }).toString()).toBe('type=films');
    expect(watchlistParamsToSearch({ type: 'all', sort: 'year', watched: null }).toString()).toBe('sort=year');
    expect(watchlistParamsToSearch({ type: 'shows', sort: 'title', watched: null }).toString()).toBe('type=shows&sort=title');
    expect(watchlistParamsToSearch({ type: 'all', sort: 'added', watched: false }).toString()).toBe('watched=false');
    expect(watchlistParamsToSearch({ type: 'films', sort: 'title', watched: true }).toString()).toBe(
      'type=films&watched=true&sort=title',
    );
  });

  it('maps defaults to undefined API params (not sent)', () => {
    expect(toWatchlistQuery({ type: 'all', sort: 'added', watched: null })).toEqual({
      type: undefined,
      sort: undefined,
      watched: undefined,
    });
    expect(toWatchlistQuery({ type: 'shows', sort: 'year', watched: false })).toEqual({
      type: 'shows',
      sort: 'year',
      watched: false,
    });
    expect(toWatchlistQuery({ type: 'all', sort: 'added', watched: true }).watched).toBe(true);
  });
});

describe('useWatchlistParams (URL is the single source of truth)', () => {
  function setup(initial: string) {
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(
        MemoryRouter,
        { initialEntries: [initial], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
        children,
      );
    return renderHook(
      () => ({ wl: useWatchlistParams(), location: useLocation(), action: useNavigationType(), navigate: useNavigate() }),
      { wrapper },
    );
  }

  it('reads the URL, invalid values as defaults, without rewriting it', () => {
    const { result } = setup('/watchlist?type=x&sort=y');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'added', watched: null });
    expect(result.current.location.search).toBe('?type=x&sort=y');
    expect(result.current.action).toBe('POP');
  });

  it('setters write or remove params, replacing the history entry', () => {
    const { result } = setup('/watchlist');
    act(() => result.current.wl.setType('films'));
    expect(result.current.location.search).toBe('?type=films');
    expect(result.current.action).toBe('REPLACE');

    act(() => result.current.wl.setSort('title'));
    expect(result.current.location.search).toBe('?type=films&sort=title');
    expect(result.current.wl.params).toEqual({ type: 'films', sort: 'title', watched: null });

    act(() => result.current.wl.setType('all'));
    expect(result.current.location.search).toBe('?sort=title');

    act(() => result.current.wl.setSort('added'));
    expect(result.current.location.search).toBe('');
    expect(result.current.action).toBe('REPLACE');
  });

  it('a second change before the first one renders builds on it (does not bring back the old value)', () => {
    const { result } = setup('/watchlist?type=films&sort=title');
    // The same setters, called before React renders the first navigation (e.g. a select change
    // right after a type click while the transition is pending).
    const { setType, setSort } = result.current.wl;
    act(() => {
      setType('all');
      setSort('added');
    });
    expect(result.current.location.search).toBe('');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'added', watched: null });
  });

  it('a URL change from outside the hook (NavBar link, Back) resets the params the setters build on', () => {
    const { result } = setup('/watchlist');
    act(() => result.current.wl.setType('films'));
    expect(result.current.location.search).toBe('?type=films');
    act(() => result.current.navigate('/watchlist'));
    expect(result.current.location.search).toBe('');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'added', watched: null });

    act(() => result.current.wl.setSort('year'));
    expect(result.current.location.search).toBe('?sort=year');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'year', watched: null });
  });

  it('showAll removes type and keeps sort', () => {
    const { result } = setup('/watchlist?type=shows&sort=year');
    act(() => result.current.wl.showAll());
    expect(result.current.location.search).toBe('?sort=year');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'year', watched: null });
  });

  it('setWatched writes or removes ?watched= (replace); showAll also removes it and keeps sort', () => {
    const { result } = setup('/watchlist?type=films&sort=title');
    act(() => result.current.wl.setWatched(false));
    expect(result.current.location.search).toBe('?type=films&watched=false&sort=title');
    expect(result.current.action).toBe('REPLACE');
    act(() => result.current.wl.setWatched(true));
    expect(result.current.wl.params).toEqual({ type: 'films', sort: 'title', watched: true });
    act(() => result.current.wl.setWatched(null));
    expect(result.current.location.search).toBe('?type=films&sort=title');

    act(() => result.current.wl.setWatched(false));
    act(() => result.current.wl.showAll());
    expect(result.current.location.search).toBe('?sort=title');
    expect(result.current.wl.params).toEqual({ type: 'all', sort: 'title', watched: null });
  });

  it('a watched change right after a type change builds on it', () => {
    const { result } = setup('/watchlist');
    const { setType, setWatched } = result.current.wl;
    act(() => {
      setType('shows');
      setWatched(true);
    });
    expect(result.current.location.search).toBe('?type=shows&watched=true');
  });
});
