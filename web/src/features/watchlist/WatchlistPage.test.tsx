import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WatchlistEntry } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp } from '../../../tests/renderWithProviders';

/** Film entries 1..n (ids from `from`), newest first. */
function filmEntries(n: number, from = 1): WatchlistEntry[] {
  return Array.from({ length: n }, (_, i) =>
    f.watchlistFilmEntry({ item: f.filmSummary({ id: from + i, title: `Film ${from + i}`, year: 2000 }) }),
  );
}

/** Serves `total` film entries, honouring ?page= / ?page_size=. */
function serveWatchlistPages(total: number, { failPage }: { failPage?: number } = {}) {
  let failed = false;
  server.use(
    http.get(`${API}/watchlist`, ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('page_size'));
      if (page === failPage && !failed) {
        failed = true;
        return apiError(500, 'internal_error');
      }
      const start = (page - 1) * size;
      return HttpResponse.json(f.page(filmEntries(Math.max(0, Math.min(size, total - start)), start + 1), { page, page_size: size, total }));
    }),
  );
}

function serveEntries(items: WatchlistEntry[], overrides: { total?: number } = {}) {
  server.use(http.get(`${API}/watchlist`, () => HttpResponse.json(f.page(items, { page_size: 50, ...overrides }))));
}

const params = (rec: ReturnType<typeof recordRequests>) =>
  rec.find('GET', '/api/watchlist').map((c) => Object.fromEntries(c.url.searchParams));

const main = () => within(screen.getByRole('main'));
const cards = () => main().getAllByRole('listitem');

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WatchlistPage', () => {
  it('renders in the AppShell: "Mi lista" while page 1 is pending, then the single <h1> "Mi lista · 1.234"', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.get(`${API}/watchlist`, async () => {
        await gate;
        return HttpResponse.json(f.page([f.watchlistFilmEntry()], { page_size: 50, total: 1234 }));
      }),
    );
    const rec = recordRequests();
    renderApp({ route: '/watchlist' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mi lista' })).toBeInTheDocument();
    expect(await screen.findAllByTestId('skeleton-card')).toHaveLength(18);
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument();
    release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Mi lista · 1.234' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(document.title).toBe('Mi lista · HomeTV');
    expect(params(rec)).toEqual([{ page: '1', page_size: '50' }]);
    rec.dispose();
  });

  it('mixes film and show cards with the library badges; "Sin identificar" only for admins; cards open the detail', async () => {
    serveEntries([
      f.watchlistFilmEntry({ item: f.filmSummary({ progress: f.progress({ watched: true }) }) }),
      f.watchlistShowEntry(),
      f.watchlistFilmEntry({ item: f.filmSummary({ id: 30, title: 'Mi_video', year: null, match_status: 'unmatched' }) }),
    ]);
    const { unmount, user, router } = renderApp({ route: '/watchlist', profile: f.adminProfile() });
    const dune = await screen.findByRole('link', { name: 'Dune (2021)' });
    expect(dune).toHaveTextContent('✓');
    expect(dune).toHaveAttribute('href', '/films/12');
    const office = screen.getByRole('link', { name: 'The Office (2005)' });
    expect(office).toHaveTextContent('150 sin ver');
    expect(office).toHaveAttribute('href', '/shows/7');
    expect(screen.getByRole('link', { name: 'Mi_video' })).toHaveTextContent('Sin identificar');
    await user.click(office);
    await waitFor(() => expect(currentUrl(router)).toBe('/shows/7'));
    unmount();

    renderApp({ route: '/watchlist' });
    expect(await screen.findByRole('link', { name: 'Mi_video' })).not.toHaveTextContent('Sin identificar');
  });

  it('type buttons and the sort select write the URL (replace) and the request params', async () => {
    const rec = recordRequests();
    const { user, router } = renderApp({ route: '/watchlist' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    const toolbar = screen.getByRole('group', { name: 'Opciones de Mi lista' });
    const type = within(toolbar).getByRole('group', { name: 'Tipo' });
    expect(within(type).getAllByRole('button').map((b) => b.textContent)).toEqual(['Todo', 'Películas', 'Series']);
    expect(within(type).getByRole('button', { name: 'Todo' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(type).getByRole('button', { name: 'Series' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/watchlist?type=shows'));
    expect(router.state.historyAction).toBe('REPLACE');
    expect(within(type).getByRole('button', { name: 'Series' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(type).getByRole('button', { name: 'Todo' })).toHaveAttribute('aria-pressed', 'false');

    const sort = within(toolbar).getByLabelText('Orden');
    expect(within(sort).getAllByRole('option').map((o) => o.textContent)).toEqual(['Añadidos recientemente', 'Título', 'Año']);
    await user.selectOptions(sort, 'Año');
    await waitFor(() => expect(currentUrl(router)).toBe('/watchlist?type=shows&sort=year'));

    await user.click(within(type).getByRole('button', { name: 'Todo' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/watchlist?sort=year'));
    await user.selectOptions(sort, 'Añadidos recientemente');
    await waitFor(() => expect(currentUrl(router)).toBe('/watchlist'));

    // Back to the defaults: the first key is still fresh (30 s), so it is served from the cache.
    await waitFor(() => expect(params(rec)).toHaveLength(4));
    expect(params(rec)).toEqual([
      { page: '1', page_size: '50' },
      { page: '1', page_size: '50', type: 'shows' },
      { page: '1', page_size: '50', type: 'shows', sort: 'year' },
      { page: '1', page_size: '50', sort: 'year' },
    ]);
    rec.dispose();
  });

  it('restores the controls from the URL (?type=films&sort=title); invalid values are the defaults', async () => {
    const rec = recordRequests();
    const { unmount } = renderApp({ route: '/watchlist?type=films&sort=title' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    expect(screen.getByRole('button', { name: 'Películas' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Orden')).toHaveValue('title');
    unmount();

    const { router } = renderApp({ route: '/watchlist?type=x&sort=y' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    expect(screen.getByRole('button', { name: 'Todo' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Orden')).toHaveValue('added');
    expect(currentUrl(router)).toBe('/watchlist?type=x&sort=y');
    await waitFor(() => expect(params(rec)).toHaveLength(2));
    expect(params(rec)).toEqual([
      { page: '1', page_size: '50', type: 'films', sort: 'title' },
      { page: '1', page_size: '50' },
    ]);
    rec.dispose();
  });

  it('an empty list shows the empty text with no action and no error styling', async () => {
    renderApp({ route: '/watchlist' });
    expect(
      await screen.findByText('Tu lista está vacía. Añade películas y series desde su ficha con «Mi lista».'),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Mi lista · 0' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ver todo' })).not.toBeInTheDocument();
  });

  it.each([
    ['films', 'No hay películas en tu lista.'],
    ['shows', 'No hay series en tu lista.'],
  ])('filtered to %s with no results: "%s" + "Ver todo" clears type and keeps sort', async (type, text) => {
    server.use(
      http.get(`${API}/watchlist`, ({ request }) =>
        HttpResponse.json(f.page(new URL(request.url).searchParams.has('type') ? [] : [f.watchlistFilmEntry()], { page_size: 50 })),
      ),
    );
    const { user, router } = renderApp({ route: `/watchlist?type=${type}&sort=title` });
    expect(await screen.findByText(text)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ver todo' }));
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/watchlist?sort=title');
  });

  it('a first-page error shows ErrorState with Reintentar under the "Mi lista" heading', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/watchlist`, () =>
        ++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.page([f.watchlistFilmEntry()], { page_size: 50 })),
      ),
    );
    const { user } = renderApp({ route: '/watchlist' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    expect(screen.getByRole('heading', { level: 1, name: 'Mi lista' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Mi lista · 1' })).toBeInTheDocument();
  });

  it('pages of 50 through to total with "Cargar más"', async () => {
    serveWatchlistPages(120);
    const rec = recordRequests();
    const { user } = renderApp({ route: '/watchlist' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Mi lista · 120' })).toBeInTheDocument();
    expect(cards()).toHaveLength(50);
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(cards()).toHaveLength(100));
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(cards()).toHaveLength(120));
    expect(screen.queryByRole('button', { name: 'Cargar más' })).not.toBeInTheDocument();
    expect(params(rec).map((p) => p.page)).toEqual(['1', '2', '3']);
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
    serveWatchlistPages(70);
    renderApp({ route: '/watchlist' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 70' });
    await waitFor(() => expect(observers.length).toBeGreaterThan(0));
    act(() => observers.at(-1)!.callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver));
    await waitFor(() => expect(cards()).toHaveLength(70));
  });

  it('a next-page error is shown inline with Reintentar; loaded items stay', async () => {
    serveWatchlistPages(120, { failPage: 2 });
    const { user } = renderApp({ route: '/watchlist' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 120' });
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    expect(await screen.findByText('No se han podido cargar más.')).toBeInTheDocument();
    expect(cards()).toHaveLength(50);
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await waitFor(() => expect(cards()).toHaveLength(100));
    expect(screen.queryByText('No se han podido cargar más.')).not.toBeInTheDocument();
  });

  it('drops an item repeated on the next page (a title hidden between pages)', async () => {
    server.use(
      http.get(`${API}/watchlist`, ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        const items = page === 1 ? filmEntries(50) : filmEntries(2, 50);
        return HttpResponse.json(f.page(items, { page, page_size: 50, total: 52 }));
      }),
    );
    const errors = vi.spyOn(console, 'error');
    const { user } = renderApp({ route: '/watchlist' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 52' });
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(screen.getByRole('link', { name: 'Film 51 (2000)' })).toBeInTheDocument());
    expect(cards()).toHaveLength(51);
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('after a toggle on /films/12, showing /watchlist again refetches the list (no manual reload)', async () => {
    const rec = recordRequests();
    const { user, router } = renderApp({ route: '/watchlist' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    expect(rec.find('GET', '/api/watchlist')).toHaveLength(1);

    await act(() => router.navigate('/films/12'));
    await user.click(await screen.findByRole('button', { name: 'Añadir a Mi lista' }));
    await waitFor(() => expect(rec.find('PUT', '/api/watchlist/films/12')).toHaveLength(1));
    await waitFor(() => expect(screen.getByRole('button', { name: /Mi lista/ })).toBeEnabled());

    serveEntries([f.watchlistFilmEntry()]);
    await act(() => router.navigate('/watchlist'));
    expect(await screen.findByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
    expect(rec.find('GET', '/api/watchlist').length).toBeGreaterThanOrEqual(2);
    rec.dispose();
  });
});
