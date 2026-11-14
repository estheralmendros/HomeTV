import { screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { runInvalidation } from '@/api/queryClient';
import type { MediaFile } from '@/api/types';
import { getClientCaps } from '@/player/capabilities';
import { selectVersion, startPosition } from '@/player/versions';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { findProbedLocation, renderWithProviders } from '../../../tests/renderWithProviders';
import { HomePage } from './HomePage';

function renderHome(options: Parameters<typeof renderWithProviders>[1] = {}) {
  return renderWithProviders(<HomePage />, { path: '/', route: '/', ...options });
}

function serveHome(body: ReturnType<typeof f.homeResponse>) {
  server.use(http.get(`${API}/home`, () => HttpResponse.json(body)));
}

type Endpoint = 'films' | 'shows' | 'watchlist';

function serveEndpoint(endpoint: Endpoint, body: unknown) {
  server.use(http.get(`${API}/${endpoint}`, () => HttpResponse.json(body as Record<string, unknown>)));
}

function holdEndpoint(endpoint: Endpoint) {
  server.use(
    http.get(`${API}/${endpoint}`, async () => {
      await delay('infinite');
      return HttpResponse.json({});
    }),
  );
}

function failEndpoint(endpoint: Endpoint) {
  server.use(http.get(`${API}/${endpoint}`, () => apiError(500, 'internal_error')));
}

/** A non-empty Mi lista: a film and a show entry. */
function serveWatchlist() {
  serveEndpoint('watchlist', f.page([f.watchlistFilmEntry(), f.watchlistShowEntry()]));
}

/** Waits until the given Home rows are rendered (each request answers independently). */
async function rowsRendered(...names: string[]) {
  for (const name of names) await screen.findByRole('region', { name });
}

function headings() {
  return screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
}

const ALL_ROWS = ['Seguir viendo', 'Mi lista', 'Añadidos recientemente', 'Películas', 'Series'];

const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160 });

describe('HomePage', () => {
  it('renders the non-empty rows in order and sets the title (empty list: no Mi lista)', async () => {
    renderHome();
    await rowsRendered('Seguir viendo', 'Añadidos recientemente', 'Películas', 'Series');
    await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument());
    expect(headings()).toEqual(['Seguir viendo', 'Añadidos recientemente', 'Películas', 'Series']);
    expect(document.title).toBe('Inicio · HomeTV');
  });

  it('renders all five rows in order when the list has entries', async () => {
    serveWatchlist();
    renderHome();
    await rowsRendered(...ALL_ROWS);
    expect(headings()).toEqual(ALL_ROWS);
  });

  it('hides empty rows', async () => {
    serveHome(f.homeResponse({ continue_watching: [] }));
    renderHome();
    expect(await screen.findByRole('heading', { name: 'Añadidos recientemente' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Seguir viendo' })).not.toBeInTheDocument();
  });

  it('shows two skeleton rows of 8 cards while loading', async () => {
    server.use(http.get(`${API}/home`, async () => {
      await delay('infinite');
      return HttpResponse.json(f.homeResponse());
    }));
    renderHome();
    expect(await screen.findAllByRole('progressbar', { name: 'Cargando…' })).toHaveLength(2);
    expect(screen.getAllByTestId('skeleton-card')).toHaveLength(16);
  });

  it('empty library: admins get the folders/scan hint and both links, and nothing else', async () => {
    serveHome(f.emptyHomeResponse());
    const rec = recordRequests();
    renderHome({ profile: f.adminProfile() });
    expect(
      await screen.findByText(
        'Tu biblioteca está vacía. Añade las carpetas de tus vídeos en Administración → Bibliotecas o ejecuta un escaneo.',
      ),
    ).toBeInTheDocument();
    const libraries = screen.getByRole('link', { name: 'Ir a Administración → Bibliotecas' });
    const scan = screen.getByRole('link', { name: 'Ir a Administración → Escaneo' });
    expect(libraries).toHaveAttribute('href', '/admin/libraries');
    expect(scan).toHaveAttribute('href', '/admin/scan');
    expect(libraries.compareDocumentPosition(scan) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await waitFor(() => expect(rec.calls).toHaveLength(4));
    await rec.settled();
    expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
    expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument();
    rec.dispose();
  });

  it('empty library: viewers are asked to contact an admin (no admin link)', async () => {
    serveHome(f.emptyHomeResponse());
    renderHome();
    expect(await screen.findByText('La biblioteca está vacía. Pide a un administrador que ejecute un escaneo.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ir a Administración → Escaneo' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ir a Administración → Bibliotecas' })).not.toBeInTheDocument();
  });

  it('library not empty but every row empty: links to Películas and Series', async () => {
    serveHome(f.homeResponse({ continue_watching: [], recently_added: [] }));
    renderHome();
    expect(await screen.findByRole('link', { name: 'Películas' })).toHaveAttribute('href', '/films');
    expect(screen.getByRole('link', { name: 'Series' })).toHaveAttribute('href', '/shows');
  });

  it('shows an error with Reintentar that refetches', async () => {
    let calls = 0;
    server.use(http.get(`${API}/home`, () => (++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.homeResponse()))));
    const { user } = renderHome();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { name: 'Seguir viendo' })).toBeInTheDocument();
  });

  describe('Continue Watching', () => {
    it('film cards open the player without ?file= and have a Detalles link', async () => {
      const { user } = renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/watch/film/12');
      expect(within(row).getByRole('link', { name: 'Detalles de Dune' })).toHaveAttribute('href', '/films/12');
      expect(within(row).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '13');
      await user.click(within(row).getByRole('link', { name: 'Dune (2021)' }));
      expect(await findProbedLocation()).toBe('/watch/film/12');
    });

    it('film cards are 16:9 like the episodes: the backdrop, the title and the year', async () => {
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      const card = within(row).getByRole('link', { name: 'Dune (2021)' });
      expect(card.querySelector('[data-kind]')).toHaveAttribute('data-kind', 'backdrop');
      expect(card).toHaveTextContent('Dune');
      expect(card).toHaveTextContent('2021');
    });

    it('a rated film card carries the rating badge in its name', async () => {
      serveHome(f.homeResponse({ continue_watching: [f.filmSummary({ progress: f.progress(), my_rating: 4.5 })] }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Dune (2021), tu puntuación: 4,5 estrellas' })).toHaveAttribute('href', '/watch/film/12');
    });

    it('episode cards open /watch/episode/{id} without ?file=; the home media_files pick a playable sibling', async () => {
      const files: MediaFile[] = [
        f.episodeFile({ id: 501, video: HEVC, label: '2160p HEVC', duration_seconds: 1320 }),
        f.episodeFile({ id: 502, duration_seconds: 1318 }),
      ];
      const entry = f.homeEpisode({
        media_files: files,
        progress: f.progress({ media_file_id: 501, position_seconds: 600, duration_seconds: 1320 }),
      });
      serveHome(f.homeResponse({ continue_watching: [entry] }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      const card = within(row).getByRole('link', { name: 'The Office · T2 · E3 · Office Olympics' });
      expect(card).toHaveAttribute('href', '/watch/episode/345');
      expect(card).toHaveTextContent('T2 · E3 · Office Olympics');
      expect(within(row).getByRole('link', { name: 'Detalles de The Office' })).toHaveAttribute('href', '/shows/7');

      // What the player will start from the home payload under Chrome caps (no HEVC).
      const chosen = selectVersion(entry.media_files, getClientCaps(), entry.progress);
      expect(chosen?.id).toBe(502);
      expect(startPosition(chosen!, entry.media_files, entry.progress)).toEqual({ seconds: 600, differentVersion: false });
    });

    it('a non-matching sibling would start at 0 with the different-version notice', () => {
      const files = [
        f.episodeFile({ id: 501, video: HEVC, duration_seconds: 1320 }),
        f.episodeFile({ id: 502, duration_seconds: 1200 }),
      ];
      const p = f.progress({ media_file_id: 501, position_seconds: 600, duration_seconds: 1320 });
      const chosen = selectVersion(files, getClientCaps(), p);
      expect(chosen?.id).toBe(502);
      expect(startPosition(chosen!, files, p)).toEqual({ seconds: 0, differentVersion: true });
    });

    it('a multi-part film card links without ?file=; after Part 1 ended the default is Part 2 at 0', async () => {
      const film = f.filmSummary({
        id: 20,
        title: 'Kill Bill: Vol. 1',
        year: 2003,
        progress: f.progress({ media_file_id: 201, position_seconds: 2990, duration_seconds: 3000 }),
      });
      serveHome(f.homeResponse({ continue_watching: [film] }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Kill Bill: Vol. 1 (2003)' })).toHaveAttribute('href', '/watch/film/20');

      const parts = [
        f.mediaFile({ id: 201, part_number: 1, duration_seconds: 3000 }),
        f.mediaFile({ id: 202, part_number: 2, duration_seconds: 3100 }),
      ];
      const chosen = selectVersion(parts, getClientCaps(), film.progress);
      expect(chosen?.id).toBe(202);
      expect(startPosition(chosen!, parts, film.progress).seconds).toBe(0);
    });
  });

  describe('Next Up episodes (part of Continue Watching)', () => {
    it('one "Seguir viendo" row holds the in-progress film and the Next Up episode; no "A continuación" row', async () => {
      const nextUp = f.homeEpisode({ id: 346, episode_number: 4, title: 'The Fire', progress: null });
      serveHome(f.homeResponse({ continue_watching: [f.filmSummary({ progress: f.progress() }), nextUp] }));
      const { user } = renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/watch/film/12');
      const card = within(row).getByRole('link', { name: 'The Office · T2 · E4 · The Fire' });
      expect(card).toHaveAttribute('href', '/watch/episode/346');
      expect(within(card).queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument();
      expect(within(row).getAllByRole('progressbar')).toHaveLength(1);
      expect(within(row).getByRole('link', { name: 'Detalles de The Office' })).toHaveAttribute('href', '/shows/7');
      expect(screen.queryByRole('heading', { name: 'A continuación' })).not.toBeInTheDocument();
      await user.click(card);
      expect(await findProbedLocation()).toBe('/watch/episode/346');
    });

    it('falls back to "Episodio N" without a title', async () => {
      serveHome(f.homeResponse({ continue_watching: [f.homeEpisode({ id: 350, episode_number: 9, title: null, progress: null })] }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'The Office · T2 · E9 · Episodio 9' })).toHaveAttribute('href', '/watch/episode/350');
    });
  });

  it('Recently Added cards open the detail pages; shows carry an "N sin ver" badge', async () => {
    renderHome();
    const row = await screen.findByRole('region', { name: 'Añadidos recientemente' });
    expect(within(row).getByRole('link', { name: 'Heat (1995)' })).toHaveAttribute('href', '/films/13');
    const show = within(row).getByRole('link', { name: 'The Office (2005)' });
    expect(show).toHaveAttribute('href', '/shows/7');
    expect(show).toHaveTextContent('150 sin ver');
  });

  it('a fully watched show has no badge', async () => {
    serveHome(f.homeResponse({ recently_added: [f.showSummary({ unwatched_episode_count: 0 })] }));
    renderHome();
    const row = await screen.findByRole('region', { name: 'Añadidos recientemente' });
    expect(within(row).getByRole('link', { name: 'The Office (2005)' })).not.toHaveTextContent('sin ver');
  });
  describe('Mi lista, Películas and Series rows', () => {
    it('requests the four endpoints once each, with the documented params', async () => {
      const rec = recordRequests();
      renderHome();
      await waitFor(() => expect(rec.calls).toHaveLength(4));
      await rec.settled();
      for (const path of ['/api/home', '/api/films', '/api/shows', '/api/watchlist']) {
        expect(rec.find('GET', path), path).toHaveLength(1);
      }
      for (const path of ['/api/films', '/api/shows']) {
        const params = rec.find('GET', path)[0]!.url.searchParams;
        expect(params.get('sort')).toBe('added');
        expect(params.get('order')).toBe('desc');
        expect(params.get('page')).toBe('1');
        expect(params.get('page_size')).toBe('20');
      }
      const list = rec.find('GET', '/api/watchlist')[0]!.url.searchParams;
      expect(list.get('sort')).toBe('added');
      expect(list.get('page')).toBe('1');
      expect(list.get('page_size')).toBe('20');
      expect(list.has('type')).toBe(false);
      expect(list.has('watched')).toBe(false);
      rec.dispose();
    });

    it('the three rows start while /api/home is still pending', async () => {
      server.use(http.get(`${API}/home`, async () => {
        await delay('infinite');
        return HttpResponse.json(f.homeResponse());
      }));
      const rec = recordRequests();
      renderHome();
      await waitFor(() =>
        expect(rec.calls.map((c) => c.url.pathname).sort()).toEqual(['/api/films', '/api/home', '/api/shows', '/api/watchlist']),
      );
      rec.dispose();
    });

    it('Mi lista shows both kinds, keeps a watched film, and ends with "Ver todo" to /watchlist', async () => {
      serveEndpoint('watchlist', f.page([f.watchlistFilmEntry({ item: f.filmSummary({ progress: f.progress({ watched: true }) }) }), f.watchlistShowEntry()]));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Mi lista' });
      expect(within(row).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/films/12');
      const show = within(row).getByRole('link', { name: 'The Office (2005)' });
      expect(show).toHaveAttribute('href', '/shows/7');
      expect(show).toHaveTextContent('150 sin ver');
      const seeAll = within(row).getByRole('link', { name: 'Ver todo: Mi lista' });
      expect(seeAll).toHaveAttribute('href', '/watchlist');
      expect(seeAll).toHaveTextContent('Ver todo');
      expect(within(row).getByText('✓')).toBeInTheDocument();
      expect(within(row).getByTestId('row-track').lastElementChild).toBe(seeAll);
    });

    it('Películas and Series show their cards and end with "Ver todo" to the added-sorted screens', async () => {
      renderHome();
      const films = await screen.findByRole('region', { name: 'Películas' });
      expect(within(films).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/films/12');
      const filmsAll = within(films).getByRole('link', { name: 'Ver todo: Películas' });
      expect(filmsAll).toHaveAttribute('href', '/films?sort=added');
      expect(within(films).getByTestId('row-track').lastElementChild).toBe(filmsAll);
      const shows = await screen.findByRole('region', { name: 'Series' });
      expect(within(shows).getByRole('link', { name: 'The Office (2005)' })).toHaveAttribute('href', '/shows/7');
      const showsAll = within(shows).getByRole('link', { name: 'Ver todo: Series' });
      expect(showsAll).toHaveAttribute('href', '/shows?sort=added');
      expect(within(shows).getByTestId('row-track').lastElementChild).toBe(showsAll);
    });

    it('Seguir viendo and Añadidos recientemente have no "Ver todo"', async () => {
      renderHome();
      await rowsRendered('Películas', 'Series');
      expect(within(screen.getByRole('region', { name: 'Seguir viendo' })).queryByRole('link', { name: /Ver todo/ })).not.toBeInTheDocument();
      expect(within(screen.getByRole('region', { name: 'Añadidos recientemente' })).queryByRole('link', { name: /Ver todo/ })).not.toBeInTheDocument();
    });

    it('"Ver todo" is shown when total equals the item count (1 of 1)', async () => {
      serveEndpoint('films', f.page([f.filmSummary()], { total: 1 }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Películas' });
      expect(within(row).getByRole('link', { name: 'Ver todo: Películas' })).toBeInTheDocument();
    });

    it('"Ver todo" navigates', async () => {
      const { user } = renderHome();
      const row = await screen.findByRole('region', { name: 'Películas' });
      await user.click(within(row).getByRole('link', { name: 'Ver todo: Películas' }));
      expect(await findProbedLocation()).toBe('/films?sort=added');
    });

    it.each([
      ['Mi lista', 'watchlist'],
      ['Películas', 'films'],
      ['Series', 'shows'],
    ] as const)('hides %s when it has no items; the other rows stay', async (title, endpoint) => {
      serveWatchlist();
      serveEndpoint(endpoint, f.page([]));
      renderHome();
      await rowsRendered(...ALL_ROWS.filter((n) => n !== title));
      await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument());
      expect(screen.queryByRole('heading', { name: title })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: `Ver todo: ${title}` })).not.toBeInTheDocument();
    });

    it.each([
      ['Mi lista', 'watchlist'],
      ['Películas', 'films'],
      ['Series', 'shows'],
    ] as const)('hides %s when its request fails, without an alert', async (title, endpoint) => {
      serveWatchlist();
      failEndpoint(endpoint);
      renderHome();
      await rowsRendered(...ALL_ROWS.filter((n) => n !== title));
      await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument());
      expect(screen.queryByRole('heading', { name: title })).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it.each([
      ['Mi lista', 'watchlist'],
      ['Películas', 'films'],
      ['Series', 'shows'],
    ] as const)('a pending %s shows one skeleton row while the others render', async (title, endpoint) => {
      serveWatchlist();
      holdEndpoint(endpoint);
      renderHome();
      await rowsRendered(...ALL_ROWS.filter((n) => n !== title));
      expect(screen.getAllByRole('progressbar', { name: 'Cargando…' })).toHaveLength(1);
      expect(screen.queryByRole('heading', { name: title })).not.toBeInTheDocument();
    });

    it('nothingToShow: no new rows and no skeletons, even when the new rows have items', async () => {
      serveHome(f.homeResponse({ continue_watching: [], recently_added: [] }));
      serveWatchlist();
      const rec = recordRequests();
      renderHome();
      expect(await screen.findByRole('link', { name: 'Películas' })).toHaveAttribute('href', '/films');
      await waitFor(() => expect(rec.calls).toHaveLength(4));
      await rec.settled();
      expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
      expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument();
      rec.dispose();
    });

    it('while /api/home is pending: two skeleton rows only, even when the new rows answered', async () => {
      server.use(http.get(`${API}/home`, async () => {
        await delay('infinite');
        return HttpResponse.json(f.homeResponse());
      }));
      const { queryClient } = renderHome();
      await waitFor(() => expect(queryClient.getQueryData(['films', 'list', 'homeRow'])).toBeDefined());
      await waitFor(() => expect(queryClient.getQueryData(['shows', 'list', 'homeRow'])).toBeDefined());
      await waitFor(() => expect(queryClient.getQueryData(['watchlist', 'homeRow'])).toBeDefined());
      expect(screen.getAllByRole('progressbar')).toHaveLength(2);
      expect(screen.getAllByTestId('skeleton-card')).toHaveLength(16);
      expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
    });

    it('/api/home failing hides the rows even when the new rows answered', async () => {
      server.use(http.get(`${API}/home`, () => apiError(500, 'internal_error')));
      const { queryClient } = renderHome();
      expect(await screen.findByRole('alert')).toBeInTheDocument();
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));
      expect(queryClient.getQueryData(['films', 'list', 'homeRow'])).toBeDefined();
      expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
    });

    it('Reintentar after a total outage refetches the four endpoints and restores five rows', async () => {
      const calls: Record<string, number> = { home: 0, films: 0, shows: 0, watchlist: 0 };
      const success: Record<string, unknown> = {
        home: f.homeResponse(),
        films: f.page([f.filmSummary()]),
        shows: f.page([f.showSummary()]),
        watchlist: f.page([f.watchlistFilmEntry()]),
      };
      for (const endpoint of Object.keys(calls)) {
        server.use(
          http.get(`${API}/${endpoint}`, () =>
            ++calls[endpoint]! === 1 ? apiError(500, 'internal_error') : HttpResponse.json(success[endpoint] as Record<string, unknown>),
          ),
        );
      }
      const rec = recordRequests();
      const { user, queryClient } = renderHome();
      await screen.findByRole('alert');
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));
      await user.click(screen.getByRole('button', { name: 'Reintentar' }));
      await rowsRendered(...ALL_ROWS);
      for (const path of ['/api/home', '/api/films', '/api/shows', '/api/watchlist']) {
        expect(rec.find('GET', path), path).toHaveLength(2);
      }
      rec.dispose();
    });

    it('Reintentar with only /api/home failed refetches only /api/home', async () => {
      let homeCalls = 0;
      server.use(http.get(`${API}/home`, () => (++homeCalls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.homeResponse()))));
      const rec = recordRequests();
      const { user, queryClient } = renderHome();
      await screen.findByRole('alert');
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));
      await user.click(screen.getByRole('button', { name: 'Reintentar' }));
      await rowsRendered('Seguir viendo', 'Películas', 'Series');
      expect(rec.find('GET', '/api/home')).toHaveLength(2);
      expect(rec.find('GET', '/api/films')).toHaveLength(1);
      expect(rec.find('GET', '/api/shows')).toHaveLength(1);
      expect(rec.find('GET', '/api/watchlist')).toHaveLength(1);
      rec.dispose();
    });

    it('a failed refetch keeps the row and its cards', async () => {
      const { queryClient } = renderHome();
      await rowsRendered('Películas');
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));
      failEndpoint('films');
      await queryClient.invalidateQueries({ queryKey: ['films'] });
      await waitFor(() => expect(queryClient.getQueryState(['films', 'list', 'homeRow'])?.status).toBe('error'));
      const row = screen.getByRole('region', { name: 'Películas' });
      expect(within(row).getByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
      expect(within(row).getByRole('link', { name: 'Ver todo: Películas' })).toBeInTheDocument();
    });

    it('watchlistChanged refetches the list: Mi lista appears when it was empty', async () => {
      const { queryClient } = renderHome();
      await rowsRendered('Películas', 'Series');
      await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Cargando…' })).not.toBeInTheDocument());
      expect(screen.queryByRole('heading', { name: 'Mi lista' })).not.toBeInTheDocument();
      serveWatchlist();
      await runInvalidation(queryClient, { type: 'watchlistChanged', kind: 'film', id: 12 });
      expect(await screen.findByRole('region', { name: 'Mi lista' })).toBeInTheDocument();
    });
  });
});
