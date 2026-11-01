import { screen, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
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

const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160 });

describe('HomePage', () => {
  it('renders the non-empty rows in order and sets the title', async () => {
    renderHome();
    const headings = await screen.findAllByRole('heading', { level: 2 });
    expect(headings.map((h) => h.textContent)).toEqual(['Seguir viendo', 'Añadidos recientemente']);
    expect(document.title).toBe('Inicio · HomeTV');
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

  it('empty library: admins get the folders/scan hint and both links, without list requests', async () => {
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
    await rec.settled();
    expect(rec.find('GET', '/api/films')).toHaveLength(0);
    expect(rec.find('GET', '/api/shows')).toHaveLength(0);
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

    it('a rated film card carries the rating badge in its name (ratings prd2 FR-W11)', async () => {
      serveHome(f.homeResponse({ continue_watching: [f.filmSummary({ progress: f.progress(), my_rating: 4.5 })] }));
      renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Dune (2021), tu puntuación: 4,5 estrellas' })).toHaveAttribute('href', '/watch/film/12');
    });

    it('episode cards open /watch/episode/{id} without ?file=; the home media_files pick a playable sibling (D11)', async () => {
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

      // What the player (phase 4) will start from the home payload under Chrome caps (no HEVC).
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

  describe('Next Up episodes (prd.md Rev 13: part of Continue Watching)', () => {
    it('one "Seguir viendo" row holds the in-progress film and the Next Up episode; no "A continuación" row', async () => {
      const nextUp = f.homeEpisode({ id: 346, episode_number: 4, title: 'The Fire', progress: null });
      serveHome(f.homeResponse({ continue_watching: [f.filmSummary({ progress: f.progress() }), nextUp] }));
      const { user } = renderHome();
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });
      expect(within(row).getByRole('link', { name: 'Dune (2021)' })).toHaveAttribute('href', '/watch/film/12');
      const card = within(row).getByRole('link', { name: 'The Office · T2 · E4 · The Fire' });
      expect(card).toHaveAttribute('href', '/watch/episode/346');
      expect(within(card).queryByRole('progressbar')).not.toBeInTheDocument();
      expect(within(row).getAllByRole('progressbar')).toHaveLength(1);
      expect(within(row).getByRole('link', { name: 'Detalles de The Office' })).toHaveAttribute('href', '/shows/7');
      expect(screen.queryByRole('heading', { name: 'A continuación' })).not.toBeInTheDocument();
      expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Seguir viendo', 'Añadidos recientemente']);
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
});
