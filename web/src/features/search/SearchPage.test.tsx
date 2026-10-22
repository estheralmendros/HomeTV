import { screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderWithProviders } from '../../../tests/renderWithProviders';
import { NavSearch } from './NavSearch';
import { SearchPage } from './SearchPage';

function renderSearch(route = '/search', options: Parameters<typeof renderWithProviders>[1] = {}) {
  return renderWithProviders(
    <>
      <NavSearch />
      <SearchPage />
    </>,
    { path: '/search', route, ...options },
  );
}

describe('SearchPage', () => {
  it('empty q: shows the hint and sends no request', async () => {
    const rec = recordRequests();
    renderSearch('/search?q=%20%20');
    expect(screen.getByRole('heading', { level: 1, name: 'Buscar' })).toBeInTheDocument();
    expect(screen.getByText('Busca películas y series por título.')).toBeInTheDocument();
    expect(document.title).toBe('Buscar · HomeTV');
    await rec.settled();
    expect(rec.find('GET', '/api/search')).toHaveLength(0);
    rec.dispose();
  });

  it('shows "Películas (n)" and "Series (n)" sections as poster grids', async () => {
    const rec = recordRequests();
    renderSearch('/search?q=dune');
    const films = await screen.findByRole('region', { name: 'Películas (1)' });
    expect(within(films).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/films/12');
    const shows = screen.getByRole('region', { name: 'Series (1)' });
    expect(within(shows).getByRole('link', { name: 'The Office (2005)' })).toHaveAttribute('href', '/shows/7');
    expect(Object.fromEntries(rec.find('GET', '/api/search')[0]!.url.searchParams)).toEqual({ q: 'dune', limit: '20' });
    rec.dispose();
  });

  it('hides a section without results', async () => {
    server.use(http.get(`${API}/search`, () => HttpResponse.json({ films: [f.filmSummary(), f.filmSummary({ id: 2, title: 'Dune: Parte dos', year: 2024 })], shows: [] })));
    renderSearch('/search?q=dune');
    expect(await screen.findByRole('region', { name: 'Películas (2)' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /^Series/ })).not.toBeInTheDocument();
  });

  it('says there are no results when both sections are empty', async () => {
    server.use(http.get(`${API}/search`, () => HttpResponse.json({ films: [], shows: [] })));
    renderSearch('/search?q=zzz');
    expect(await screen.findByText('No hay resultados para «zzz».')).toBeInTheDocument();
  });

  it('shows a skeleton while loading and an error with Reintentar', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/search`, async () => {
        calls += 1;
        if (calls === 1) {
          await delay(50);
          return apiError(500, 'internal_error');
        }
        return HttpResponse.json({ films: [f.filmSummary()], shows: [] });
      }),
    );
    const { user } = renderSearch('/search?q=dune');
    expect(screen.getByRole('progressbar', { name: 'Cargando…' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('region', { name: 'Películas (1)' })).toBeInTheDocument();
  });

  it('NavSearch on /search updates q in the URL (replace, debounced) and the results follow', async () => {
    server.use(
      http.get(`${API}/search`, ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({ films: q === 'heat' ? [f.filmSummary({ id: 13, title: 'Heat', year: 1995 })] : [], shows: [] });
      }),
    );
    const { user, router } = renderSearch('/search');
    const input = screen.getByRole('searchbox', { name: 'Buscar títulos' });
    await user.type(input, 'heat');
    expect(await screen.findByRole('link', { name: 'Heat (1995)' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/search?q=heat');
    expect(router.state.historyAction).toBe('REPLACE');

    await user.clear(input);
    expect(await screen.findByText('Busca películas y series por título.')).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/search');
  });

  it('NavSearch reflects the q of the URL, and the page input (mobile) edits it too', async () => {
    const { user, router } = renderSearch('/search?q=dune');
    expect(screen.getByRole('searchbox', { name: 'Buscar títulos' })).toHaveValue('dune');
    const pageInput = screen.getByRole('searchbox', { name: 'Buscar películas y series' });
    expect(pageInput).toHaveValue('dune');
    await user.clear(pageInput);
    await user.type(pageInput, 'office');
    await waitFor(() => expect(currentUrl(router)).toBe('/search?q=office'));
    await waitFor(() => expect(screen.getByRole('searchbox', { name: 'Buscar títulos' })).toHaveValue('office'));
  });
});
