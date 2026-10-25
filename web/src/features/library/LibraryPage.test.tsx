import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FilmSummary, LibraryType, ShowSummary } from '@/api/types';
import { queryKeys } from '@/api/queryKeys';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderWithProviders } from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';
import { LibraryPage } from './LibraryPage';

function films(n: number, from = 1): FilmSummary[] {
  return Array.from({ length: n }, (_, i) => f.filmSummary({ id: from + i, title: `Film ${from + i}`, year: 2000 }));
}

/** Serves `total` films, page_size per request, honouring ?page=. */
function serveFilmPages(total: number, { failPage }: { failPage?: number } = {}) {
  let failed = false;
  server.use(
    http.get(`${API}/films`, ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('page_size'));
      if (page === failPage && !failed) {
        failed = true;
        return apiError(500, 'internal_error');
      }
      const start = (page - 1) * size;
      return HttpResponse.json(f.page(films(Math.max(0, Math.min(size, total - start)), start + 1), { page, page_size: size, total }));
    }),
  );
}

function renderLibrary(type: LibraryType = 'films', route = `/${type}`, options: Parameters<typeof renderWithProviders>[1] = {}) {
  return renderWithProviders(<LibraryPage type={type} />, { path: `/${type}`, route, ...options });
}

const lastListParams = (rec: ReturnType<typeof recordRequests>, path = '/api/films') => {
  const calls = rec.find('GET', path);
  return Object.fromEntries(calls.at(-1)?.url.searchParams ?? []);
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LibraryPage (films)', () => {
  it('requests the first page with the defaults and shows the title, count and posters', async () => {
    server.use(http.get(`${API}/films`, () => HttpResponse.json(f.page([f.filmSummary()], { total: 1234 }))));
    const rec = recordRequests();
    renderLibrary();
    expect(screen.getByRole('heading', { level: 1, name: 'Películas' })).toBeInTheDocument();
    expect(await screen.findByText('1.234 películas')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/films/12');
    expect(lastListParams(rec)).toEqual({ page: '1', page_size: '60', sort: 'title', order: 'asc' });
    expect(document.title).toBe('Películas · HomeTV');
    rec.dispose();
  });

  it('shows 18 skeleton cards while the first page loads', async () => {
    server.use(http.get(`${API}/films`, async () => {
      await delay('infinite');
      return HttpResponse.json(f.page([]));
    }));
    renderLibrary();
    expect(await screen.findAllByTestId('skeleton-card')).toHaveLength(18);
  });

  it('film cards show progress or ✓; admins see "Sin identificar" tags, viewers do not', async () => {
    const items = [
      f.filmSummary({ id: 1, title: 'Alien', year: 1979, progress: f.progress({ watched: true }) }),
      f.filmSummary({ id: 2, title: 'Blade Runner', year: 2017, progress: f.progress({ position_seconds: 4680, duration_seconds: 9360 }) }),
      f.filmSummary({ id: 3, title: 'Dune', year: 2021, match_status: 'unmatched' }),
    ];
    server.use(http.get(`${API}/films`, () => HttpResponse.json(f.page(items))));
    const { unmount } = renderLibrary('films', '/films', { profile: f.adminProfile() });
    const alien = await screen.findByRole('link', { name: 'Alien (1979)' });
    expect(alien).toHaveTextContent('✓');
    expect(within(screen.getByRole('link', { name: 'Blade Runner (2017)' })).getByRole('progressbar')).toHaveAttribute(
      'aria-valuenow',
      '50',
    );
    expect(screen.getByRole('link', { name: 'Dune (2021)' })).toHaveTextContent('Sin identificar');
    unmount();

    renderLibrary();
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).not.toHaveTextContent('Sin identificar');
  });

  it('toolbar changes are sent as request params and reflected in the URL', async () => {
    const rec = recordRequests();
    const { user, router, queryClient } = renderLibrary();
    await screen.findByRole('link', { name: 'Dune (2021)' });

    await user.type(screen.getByRole('searchbox', { name: 'Filtrar por título' }), '  dune ');
    await waitFor(() => expect(lastListParams(rec).q).toBe('dune'));
    expect(currentUrl(router)).toBe('/films?q=dune');

    await user.selectOptions(screen.getByLabelText('Orden'), 'Año');
    await waitFor(() => expect(lastListParams(rec)).toMatchObject({ sort: 'year', order: 'desc' }));

    await user.click(screen.getByRole('button', { name: 'Desc' }));
    await waitFor(() => expect(lastListParams(rec)).toMatchObject({ sort: 'year', order: 'asc' }));
    expect(screen.getByRole('button', { name: 'Asc' })).toBeInTheDocument();

    const genre = screen.getByLabelText('Género');
    await waitFor(() => expect(within(genre).getByRole('option', { name: 'Drama (120)' })).toBeInTheDocument());
    expect(within(genre).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Todos los géneros',
      'Drama (120)',
      'Comedia (40)',
    ]);
    await user.selectOptions(genre, 'Drama (120)');
    await waitFor(() => expect(lastListParams(rec).genre).toBe('18'));

    await user.type(screen.getByLabelText('Desde'), '1990');
    await user.type(screen.getByLabelText('Hasta'), '2009');
    await waitFor(() => expect(lastListParams(rec)).toMatchObject({ year_from: '1990', year_to: '2009' }));
    expect(currentUrl(router)).toBe('/films?q=dune&sort=year&order=asc&genre=18&year_from=1990&year_to=2009');
    expect(rec.find('GET', '/api/genres')[0]?.url.searchParams.get('type')).toBe('films');

    // prd2 §5 step 2: "Estado" (Todas / Vistas / No vistas) → watched omitted / true / false.
    const state = screen.getByLabelText('Estado');
    expect(within(state).getAllByRole('option').map((o) => o.textContent)).toEqual(['Todas', 'Vistas', 'No vistas']);
    await user.selectOptions(state, 'No vistas');
    await waitFor(() => expect(lastListParams(rec).watched).toBe('false'));
    expect(currentUrl(router)).toBe('/films?q=dune&sort=year&order=asc&genre=18&year_from=1990&year_to=2009&watched=false');
    await user.selectOptions(state, 'Vistas');
    await waitFor(() => expect(lastListParams(rec).watched).toBe('true'));
    // Drop the cached unfiltered page so "Todas" sends a request: it must omit `watched`.
    queryClient.removeQueries({ queryKey: queryKeys.libraryAll('films'), type: 'inactive' });
    await user.selectOptions(state, 'Todas');
    await waitFor(() => expect(currentUrl(router)).toBe('/films?q=dune&sort=year&order=asc&genre=18&year_from=1990&year_to=2009'));
    await waitFor(() => expect(lastListParams(rec)).toEqual({ page: '1', page_size: '60', q: 'dune', sort: 'year', order: 'asc', genre: '18', year_from: '1990', year_to: '2009' }));
    rec.dispose();
  });

  it('restores the toolbar from the URL', async () => {
    const rec = recordRequests();
    renderLibrary('films', '/films?q=dune&sort=added&order=asc&genre=35&year_from=1990&year_to=2009&watched=true');
    await screen.findByRole('link', { name: 'Dune (2021)' });
    expect(screen.getByText('1 película')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Filtrar por título' })).toHaveValue('dune');
    expect(screen.getByLabelText('Orden')).toHaveValue('added');
    expect(screen.getByRole('button', { name: 'Asc' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Género')).toHaveValue('35'));
    expect(screen.getByLabelText('Desde')).toHaveValue(1990);
    expect(screen.getByLabelText('Hasta')).toHaveValue(2009);
    expect(screen.getByLabelText('Estado')).toHaveValue('true');
    expect(lastListParams(rec)).toEqual({
      page: '1', page_size: '60', q: 'dune', sort: 'added', order: 'asc', genre: '35', year_from: '1990', year_to: '2009', watched: 'true',
    });
    rec.dispose();
  });

  it('From > To shows an inline error and sends no request', async () => {
    const rec = recordRequests();
    renderLibrary('films', '/films?year_from=2010&year_to=2000');
    expect(await screen.findByText('El año inicial no puede ser posterior al final.')).toBeInTheDocument();
    await rec.settled();
    expect(rec.find('GET', '/api/films')).toHaveLength(0);
    rec.dispose();
  });

  it('pages through to total with "Cargar más" (no IntersectionObserver)', async () => {
    serveFilmPages(130);
    const rec = recordRequests();
    const { user } = renderLibrary();
    expect(await screen.findByText('130 películas')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(60);

    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(120));
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(130));
    expect(screen.queryByRole('button', { name: 'Cargar más' })).not.toBeInTheDocument();
    expect(rec.find('GET', '/api/films').map((c) => c.url.searchParams.get('page'))).toEqual(['1', '2', '3']);
    rec.dispose();
  });

  it('loads the next page when the sentinel comes into view', async () => {
    const observers: Array<{ callback: IntersectionObserverCallback }> = [];
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(public callback: IntersectionObserverCallback) {
          observers.push(this);
        }
        observe() {}
        disconnect() {}
      },
    );
    serveFilmPages(70);
    renderLibrary();
    expect(await screen.findByText('70 películas')).toBeInTheDocument();
    await waitFor(() => expect(observers.length).toBeGreaterThan(0));
    observers.at(-1)!.callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(70));
  });

  it('a next-page error is shown inline with Reintentar; loaded items stay', async () => {
    serveFilmPages(130, { failPage: 2 });
    const { user } = renderLibrary();
    await screen.findByText('130 películas');
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    expect(await screen.findByText('No se han podido cargar más.')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(60);
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(120));
    expect(screen.queryByText('No se han podido cargar más.')).not.toBeInTheDocument();
  });

  it('a first-page error shows ErrorState with Reintentar', async () => {
    let calls = 0;
    server.use(http.get(`${API}/films`, () => (++calls === 1 ? apiError(502, 'tmdb_unavailable') : HttpResponse.json(f.page([f.filmSummary()])))));
    const { user } = renderLibrary();
    expect(await screen.findByRole('alert')).toHaveTextContent('TMDB no responde. Vuelve a intentarlo más tarde.');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
  });

  it('empty with filters: "Ninguna película coincide…" and Borrar filtros clears the URL', async () => {
    server.use(
      http.get(`${API}/films`, ({ request }) =>
        HttpResponse.json(f.page(new URL(request.url).searchParams.has('q') ? [] : [f.filmSummary()])),
      ),
    );
    const { user, router } = renderLibrary('films', '/films?q=zzz&sort=year');
    expect(await screen.findByText('Ninguna película coincide con estos filtros.')).toBeInTheDocument();
    const clearButtons = screen.getAllByRole('button', { name: 'Borrar filtros' });
    await user.click(clearButtons.at(-1)!);
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/films?sort=year');
    expect(screen.getByRole('searchbox', { name: 'Filtrar por título' })).toHaveValue('');
  });

  it('empty without filters: the empty-library state', async () => {
    server.use(http.get(`${API}/films`, () => HttpResponse.json(f.page([]))));
    renderLibrary();
    expect(await screen.findByText('La biblioteca está vacía. Pide a un administrador que ejecute un escaneo.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Borrar filtros' })).not.toBeInTheDocument();
  });

  it('desktop: the filter controls are inline and there is no "Filtros" button', async () => {
    renderLibrary();
    await screen.findByRole('link', { name: 'Dune (2021)' });
    const toolbar = screen.getByRole('group', { name: 'Filtros de la biblioteca' });
    expect(within(toolbar).getByLabelText('Orden')).toBeInTheDocument();
    expect(within(toolbar).getByLabelText('Género')).toBeInTheDocument();
    expect(within(toolbar).getByLabelText('Desde')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Filtros' })).not.toBeInTheDocument();
  });

  it('phone (< 600 px): only the title filter and the "Filtros" button; resizing swaps them', async () => {
    setViewportWidth(360);
    renderLibrary();
    await screen.findByRole('link', { name: 'Dune (2021)' });
    const toolbar = screen.getByRole('group', { name: 'Filtros de la biblioteca' });
    expect(within(toolbar).getByRole('button', { name: 'Filtros' })).toBeInTheDocument();
    expect(within(toolbar).queryByLabelText('Orden')).not.toBeInTheDocument();
    expect(within(toolbar).queryByLabelText('Género')).not.toBeInTheDocument();
    act(() => setViewportWidth(1024));
    expect(within(toolbar).queryByRole('button', { name: 'Filtros' })).not.toBeInTheDocument();
    expect(within(toolbar).getByLabelText('Orden')).toBeInTheDocument();
  });

  it('mobile filter sheet: Aplicar writes sort, order, genre, years and watched to the URL', async () => {
    setViewportWidth(360);
    const { user, router } = renderLibrary();
    await screen.findByRole('link', { name: 'Dune (2021)' });
    await user.click(screen.getByRole('button', { name: 'Filtros' }));
    const sheet = await screen.findByRole('dialog', { name: 'Filtros' });
    await user.selectOptions(within(sheet).getByLabelText('Orden'), 'Año');
    await waitFor(() => expect(within(sheet).getByRole('option', { name: 'Comedia (40)' })).toBeInTheDocument());
    await user.selectOptions(within(sheet).getByLabelText('Género'), 'Comedia (40)');
    await user.type(within(sheet).getByLabelText('Desde'), '2000');
    await user.selectOptions(within(sheet).getByLabelText('Estado'), 'Vistas');
    await user.click(within(sheet).getByRole('button', { name: 'Aplicar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(currentUrl(router)).toBe('/films?sort=year&genre=35&year_from=2000&watched=true');
  });

  it('mobile filter sheet: From > To blocks Aplicar; Borrar clears the filters', async () => {
    setViewportWidth(360);
    const { user, router } = renderLibrary('films', '/films?genre=18&watched=false');
    await screen.findByRole('link', { name: 'Dune (2021)' });
    await user.click(screen.getByRole('button', { name: 'Filtros' }));
    const sheet = await screen.findByRole('dialog', { name: 'Filtros' });
    await user.type(within(sheet).getByLabelText('Desde'), '2010');
    await user.type(within(sheet).getByLabelText('Hasta'), '2000');
    expect(within(sheet).getByText('El año inicial no puede ser posterior al final.')).toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: 'Aplicar' })).toBeDisabled();
    await user.click(within(sheet).getByRole('button', { name: 'Borrar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(currentUrl(router)).toBe('/films');
  });
});

describe('LibraryPage (shows)', () => {
  it('lists shows with unwatched badges and the shows copy', async () => {
    const items: ShowSummary[] = [f.showSummary(), f.showSummary({ id: 8, title: 'Dark', year: 2017, unwatched_episode_count: 0 })];
    server.use(http.get(`${API}/shows`, () => HttpResponse.json(f.page(items, { total: 45 }))));
    const rec = recordRequests();
    renderLibrary('shows');
    expect(screen.getByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    expect(await screen.findByText('45 series')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'The Office (2005)' })).toHaveTextContent('150 sin ver');
    expect(screen.getByRole('link', { name: 'Dark (2017)' })).not.toHaveTextContent('sin ver');
    expect(screen.getByRole('link', { name: 'Dark (2017)' })).toHaveAttribute('href', '/shows/8');
    await waitFor(() => expect(rec.find('GET', '/api/genres')[0]?.url.searchParams.get('type')).toBe('shows'));
    expect(document.title).toBe('Series · HomeTV');
    rec.dispose();
  });

  it('says "Ninguna serie coincide…" when filtered to nothing', async () => {
    server.use(http.get(`${API}/shows`, () => HttpResponse.json(f.page([]))));
    renderLibrary('shows', '/shows?genre=35');
    expect(await screen.findByText('Ninguna serie coincide con estos filtros.')).toBeInTheDocument();
  });
});
