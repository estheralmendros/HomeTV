import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { SeasonDetail, ShowDetail } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { setViewportWidth } from '../../../tests/viewport';
import { currentUrl, findProbedLocation, renderWithProviders } from '../../../tests/renderWithProviders';
import { ShowDetailPage } from './ShowDetailPage';

function serveShow(overrides: Partial<ShowDetail>) {
  const show = f.showDetail(overrides);
  server.use(http.get(`${API}/shows/:id`, () => HttpResponse.json(show)));
  return show;
}

function serveSeason(build: (n: number) => SeasonDetail | null) {
  server.use(
    http.get(`${API}/shows/:id/seasons/:n`, ({ params }) => {
      const season = build(Number(params.n));
      return season ? HttpResponse.json(season) : apiError(404, 'not_found');
    }),
  );
}

function renderShow(route = '/shows/7', options: Parameters<typeof renderWithProviders>[1] = {}) {
  return renderWithProviders(<ShowDetailPage />, { path: '/shows/:id', route, ...options });
}

const seasonRequests = (rec: ReturnType<typeof recordRequests>) =>
  rec.find('GET', /^\/api\/shows\/7\/seasons\/\d+$/).map((c) => Number(c.url.pathname.split('/').pop()));

describe('ShowDetailPage', () => {
  it('shows the header with mapped status, counts and the unwatched count', async () => {
    renderShow();
    expect(await screen.findByRole('heading', { level: 1, name: 'The Office' })).toBeInTheDocument();
    expect(screen.getByText('2005 · Finalizada · 2 temporadas · 28 episodios')).toBeInTheDocument();
    expect(screen.getByText('150 sin ver')).toBeInTheDocument();
    expect(screen.getByText('Un falso documental sobre un grupo de oficinistas…')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Reparto' })).toHaveTextContent('Steve Carell');
    expect(document.title).toBe('The Office (2005) · HomeTV');
  });

  it('hides an unknown status and does not count specials', async () => {
    serveShow({
      status: 'Something new',
      seasons: [f.showSeason({ season_number: 1, episode_count: 6 }), f.showSeason({ season_number: 0, episode_count: 3 })],
    });
    renderShow();
    expect(await screen.findByText('2005 · 1 temporada · 6 episodios')).toBeInTheDocument();
  });

  describe('primary button from next_episode', () => {
    it('"Reanudar T2 · E3" when it is in progress', async () => {
      serveShow({ next_episode: f.episodeSummary({ progress: f.progress({ media_file_id: 501, position_seconds: 300 }) }) });
      renderShow();
      expect(await screen.findByRole('link', { name: 'Reanudar T2 · E3' })).toHaveAttribute('href', '/watch/episode/345');
    });

    it('"Reproducir T2 · E3" otherwise, hidden without next_episode', async () => {
      const { unmount } = renderShow();
      expect(await screen.findByRole('link', { name: 'Reproducir T2 · E3' })).toHaveAttribute('href', '/watch/episode/345');
      unmount();
      serveShow({ next_episode: null });
      renderShow();
      await screen.findByRole('heading', { level: 1, name: 'The Office' });
      expect(screen.queryByRole('link', { name: /^(Reproducir|Reanudar) T/ })).not.toBeInTheDocument();
    });
  });

  describe('seasons', () => {
    it('defaults to the season of next_episode', async () => {
      const rec = recordRequests();
      renderShow();
      expect(await screen.findByRole('tab', { name: 'Temporada 2' })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('aria-selected', 'false');
      expect(await screen.findByText('E3 · Office Olympics')).toBeInTheDocument();
      expect(screen.getByText('Temporada 2 · 22 episodios · 10 sin ver')).toBeInTheDocument();
      expect(screen.getByText('La segunda temporada…')).toBeInTheDocument();
      expect(seasonRequests(rec)).toEqual([2]);
      rec.dispose();
    });

    it('defaults to the first season without next_episode', async () => {
      serveShow({ next_episode: null });
      renderShow();
      expect(await screen.findByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('aria-selected', 'true');
    });

    it('follows ?season= and writes the selection to the URL (desktop tabs, no select)', async () => {
      const rec = recordRequests();
      const { user, router } = renderShow('/shows/7?season=1');
      expect(await screen.findByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('aria-selected', 'true');
      await waitFor(() => expect(seasonRequests(rec)).toEqual([1]));
      expect(screen.queryByLabelText('Temporada')).not.toBeInTheDocument();

      await user.click(screen.getByRole('tab', { name: 'Temporada 2' }));
      expect(currentUrl(router)).toBe('/shows/7?season=2');
      expect(await screen.findByText('Temporada 2 · 22 episodios · 10 sin ver')).toBeInTheDocument();
      rec.dispose();
    });

    it('phone (360 px): a <select> instead of tabs, writing the selection to the URL', async () => {
      setViewportWidth(360);
      const { user, router } = renderShow('/shows/7?season=1');
      expect(await screen.findByLabelText('Temporada')).toHaveValue('1');
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
      expect(screen.queryByRole('tab')).not.toBeInTheDocument();

      await user.selectOptions(screen.getByLabelText('Temporada'), 'Temporada 2');
      expect(currentUrl(router)).toBe('/shows/7?season=2');
      expect(screen.getByLabelText('Temporada')).toHaveValue('2');
      expect(await screen.findByText('Temporada 2 · 22 episodios · 10 sin ver')).toBeInTheDocument();
    });

    it('switches to the select when the viewport shrinks below 600 px', async () => {
      renderShow('/shows/7?season=2');
      expect(await screen.findByRole('tab', { name: 'Temporada 2' })).toBeInTheDocument();
      act(() => setViewportWidth(599));
      expect(screen.queryByRole('tab')).not.toBeInTheDocument();
      expect(screen.getByLabelText('Temporada')).toHaveValue('2');
    });

    describe('keyboard (WAI-ARIA tabs, automatic activation)', () => {
      const threeSeasons = () =>
        serveShow({
          next_episode: null,
          seasons: [f.showSeason({ season_number: 1 }), f.showSeason({ season_number: 2 }), f.showSeason({ season_number: 0, name: null })],
        });

      it('only the selected tab is in the tab order', async () => {
        threeSeasons();
        const { user } = renderShow('/shows/7?season=2');
        expect(await screen.findByRole('tab', { name: 'Temporada 2' })).toHaveAttribute('tabindex', '0');
        expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('tabindex', '-1');
        expect(screen.getByRole('tab', { name: 'Especiales' })).toHaveAttribute('tabindex', '-1');
        for (let i = 0; i < 30 && document.activeElement?.getAttribute('role') !== 'tab'; i += 1) await user.tab();
        expect(screen.getByRole('tab', { name: 'Temporada 2' })).toHaveFocus();
      });

      it('ArrowRight on the active tab selects and focuses the next season, wrapping at the end', async () => {
        threeSeasons();
        const { user, router } = renderShow('/shows/7?season=1');
        const first = await screen.findByRole('tab', { name: 'Temporada 1' });
        first.focus();
        await user.keyboard('{ArrowRight}');
        expect(screen.getByRole('tab', { name: 'Temporada 2' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: 'Temporada 2' })).toHaveFocus();
        expect(currentUrl(router)).toBe('/shows/7?season=2');
        await user.keyboard('{ArrowRight}');
        expect(screen.getByRole('tab', { name: 'Especiales' })).toHaveFocus();
        await user.keyboard('{ArrowRight}');
        expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveFocus();
        expect(currentUrl(router)).toBe('/shows/7?season=1');
      });

      it('ArrowLeft wraps to the last tab; Home and End jump to the ends', async () => {
        threeSeasons();
        const { user, router } = renderShow('/shows/7?season=1');
        (await screen.findByRole('tab', { name: 'Temporada 1' })).focus();
        await user.keyboard('{ArrowLeft}');
        expect(screen.getByRole('tab', { name: 'Especiales' })).toHaveFocus();
        expect(currentUrl(router)).toBe('/shows/7?season=0');
        await user.keyboard('{Home}');
        expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveFocus();
        expect(screen.getByRole('tab', { name: 'Temporada 1' })).toHaveAttribute('aria-selected', 'true');
        await user.keyboard('{End}');
        expect(screen.getByRole('tab', { name: 'Especiales' })).toHaveAttribute('aria-selected', 'true');
        expect(currentUrl(router)).toBe('/shows/7?season=0');
      });
    });

    it('labels seasons without a name "Temporada N" and season 0 "Especiales"', async () => {
      serveShow({
        next_episode: null,
        seasons: [f.showSeason({ season_number: 1, name: null }), f.showSeason({ season_number: 0, name: null })],
      });
      renderShow();
      expect(await screen.findByRole('tab', { name: 'Temporada 1' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Especiales' })).toBeInTheDocument();
      act(() => setViewportWidth(360));
      expect(within(screen.getByLabelText('Temporada')).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Temporada 1',
        'Especiales',
      ]);
    });

    it('a 404 on the season refetches the show and falls back to the default season', async () => {
      serveShow({ seasons: [f.showSeason({ season_number: 1 }), f.showSeason({ season_number: 2 }), f.showSeason({ season_number: 3 })] });
      serveSeason((n) => (n === 3 ? null : f.seasonDetail({ season_number: n })));
      const rec = recordRequests();
      const { router } = renderShow('/shows/7?season=3');
      await waitFor(() => expect(currentUrl(router)).toBe('/shows/7'));
      expect(await screen.findByText('Temporada 2 · 22 episodios · 10 sin ver')).toBeInTheDocument();
      await waitFor(() => expect(rec.find('GET', '/api/shows/7')).toHaveLength(2));
      rec.dispose();
    });

    it('a show without seasons says no episodes were found', async () => {
      serveShow({ seasons: [], next_episode: null });
      renderShow();
      expect(await screen.findByText('No se han encontrado episodios.')).toBeInTheDocument();
    });
  });

  describe('episode list', () => {
    const season = f.seasonDetail({
      episodes: [
        f.episodeSummary({ id: 343, episode_number: 1, title: 'The Dundies', air_date: '2005-09-20', progress: f.progress({ watched: true }) }),
        f.episodeSummary({ id: 345, episode_number: 3, progress: f.progress({ position_seconds: 780, duration_seconds: 1320 }) }),
        f.episodeSummary({ id: 346, episode_number: 4, title: null, still: null, air_date: null, runtime_minutes: null, duration_seconds: 1320, overview: null }),
      ],
    });

    it('renders still/placeholder, "E3 · Título" / "Episodio N", date, runtime, progress and ✓', async () => {
      serveSeason(() => season);
      renderShow();
      expect(await screen.findByRole('link', { name: 'E1 · The Dundies' })).toHaveAttribute('href', '/watch/episode/343');
      expect(screen.getByText('20 sept 2005 · 22 min')).toBeInTheDocument();
      expect(screen.getByText('E3 · Office Olympics')).toBeInTheDocument();
      expect(screen.getByText('Quedan 9 min')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'E4 · Episodio 4' })).toHaveAttribute('href', '/watch/episode/346');
      expect(screen.getByText('22 min')).toBeInTheDocument();
      expect(screen.getAllByTestId('artwork-placeholder').length).toBeGreaterThan(0);

      const rows = screen.getAllByRole('listitem');
      expect(within(rows[0]!).getByRole('button', { name: 'Marcar como no visto' })).toHaveAttribute('aria-pressed', 'true');
      expect(within(rows[1]!).getByRole('button', { name: 'Marcar como visto' })).toBeInTheDocument();
      expect(within(rows[1]!).getByRole('link', { name: 'Reproducir E3' })).toHaveAttribute('href', '/watch/episode/345');
    });

    it('clicking the row title or play opens the player', async () => {
      const { user } = renderShow();
      await user.click(await screen.findByRole('link', { name: 'Reproducir E3' }));
      expect(await findProbedLocation()).toBe('/watch/episode/345');
    });

    it('expands the clamped overview on tap', async () => {
      serveSeason(() => f.seasonDetail({ episodes: [f.episodeSummary()] }));
      const { user } = renderShow();
      const overview = await screen.findByRole('button', { name: 'Michael y Dwight van a ver un piso…', expanded: false });
      await user.click(overview);
      expect(overview).toHaveAttribute('aria-expanded', 'true');
    });

    it('per-episode watched toggle is optimistic and rolls back on error', async () => {
      serveSeason(() => season);
      server.use(
        http.post(`${API}/watched`, async () => {
          await delay(50);
          return apiError(500, 'internal_error');
        }),
      );
      const rec = recordRequests();
      const { user } = renderShow();
      await screen.findByText('E3 · Office Olympics');
      const row = screen.getAllByRole('listitem')[1]!;
      await user.click(within(row).getByRole('button', { name: 'Marcar como visto' }));
      expect(within(row).getByRole('button', { name: 'Marcar como no visto' })).toBeInTheDocument();
      expect(await screen.findByText('Se ha producido un error inesperado (internal_error).')).toBeInTheDocument();
      expect(within(row).getByRole('button', { name: 'Marcar como visto' })).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('POST', '/api/watched')[0]?.body).toEqual({ target: 'episode', id: 345, watched: true });
      rec.dispose();
    });
  });

  describe('bulk watched actions', () => {
    it('marks the selected season watched and toasts the updated count', async () => {
      server.use(http.post(`${API}/watched`, () => HttpResponse.json({ updated: 22 })));
      const rec = recordRequests();
      const { user } = renderShow();
      await user.click(await screen.findByRole('button', { name: 'Marcar temporada como vista' }));
      expect(await screen.findByText('22 episodios marcados como vistos')).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('POST', '/api/watched')[0]?.body).toEqual({ target: 'season', id: 70, watched: true });
      rec.dispose();
    });

    it('a fully watched season offers "Marcar temporada como no vista"', async () => {
      serveShow({ seasons: [f.showSeason({ season_number: 2, unwatched_episode_count: 0 })] });
      server.use(http.post(`${API}/watched`, () => HttpResponse.json({ updated: 1 })));
      const { user } = renderShow();
      await user.click(await screen.findByRole('button', { name: 'Marcar temporada como no vista' }));
      expect(await screen.findByText('1 episodio marcado como no visto')).toBeInTheDocument();
    });

    it('marks the whole show watched / unwatched', async () => {
      server.use(http.post(`${API}/watched`, () => HttpResponse.json({ updated: 150 })));
      const rec = recordRequests();
      const { user, unmount } = renderShow();
      await user.click(await screen.findByRole('button', { name: 'Marcar serie como vista' }));
      expect(await screen.findByText('150 episodios marcados como vistos')).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('POST', '/api/watched')[0]?.body).toEqual({ target: 'show', id: 7, watched: true });
      rec.dispose();
      unmount();

      serveShow({ unwatched_episode_count: 0 });
      const { user: user2 } = renderShow();
      await user2.click(await screen.findByRole('button', { name: 'Marcar serie como no vista' }));
      expect(await screen.findByText('150 episodios marcados como no vistos')).toBeInTheDocument();
    });
  });

  it('404: says the show may be on a disconnected drive, with Volver a Series', async () => {
    server.use(http.get(`${API}/shows/:id`, () => apiError(404, 'not_found')));
    renderShow();
    expect(
      await screen.findByText(
        'Esta serie no está disponible. Puede que se haya eliminado o que sus archivos estén en un disco desconectado.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a Series' })).toHaveAttribute('href', '/shows');
  });

  it('Corregir identificación is for admins only', async () => {
    const { unmount } = renderShow();
    await screen.findByRole('heading', { level: 1, name: 'The Office' });
    expect(screen.queryByRole('button', { name: 'Corregir identificación' })).not.toBeInTheDocument();
    unmount();
    const { user } = renderShow('/shows/7', { profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await user.click(await screen.findByRole('button', { name: 'Corregir identificación' }));
    expect(await screen.findByRole('dialog', { name: 'Corregir identificación: The Office' })).toBeInTheDocument();
  });

  it('Corregir identificación applies through the real dialog; a merge redirects to the surviving show', async () => {
    server.use(
      http.get(`${API}/admin/tmdb/search`, () =>
        HttpResponse.json({ results: [f.tmdbSearchResult({ tmdb_id: 2316, title: 'The Office', year: 2005 })] }),
      ),
      http.post(`${API}/admin/shows/:id/match`, () => HttpResponse.json(f.showDetail({ id: 9 }))),
    );
    const rec = recordRequests();
    const { user, router } = renderShow('/shows/7', { profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await user.click(await screen.findByRole('button', { name: 'Corregir identificación' }));
    const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: The Office' });
    expect(within(dialog).getByRole('combobox', { name: 'Tipo' })).toHaveValue('tv');
    await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
    await user.click(await within(dialog).findByRole('radio', { name: 'The Office (2005)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/shows/9'));
    await rec.settled();
    expect(rec.find('POST', '/api/admin/shows/7/match')[0]!.body).toEqual({ tmdb_id: 2316 });
  });
});
