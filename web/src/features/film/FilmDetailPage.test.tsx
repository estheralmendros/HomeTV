import { act, screen, waitFor, within } from '@testing-library/react';
import { onlineManager } from '@tanstack/react-query';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { FilmDetail, MediaFile } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { tick } from '../../../tests/flush';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, findProbedLocation, renderWithProviders } from '../../../tests/renderWithProviders';
import { FilmDetailPage } from './FilmDetailPage';

const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160, hdr: 'hdr10' });

function serveFilm(overrides: Partial<FilmDetail>) {
  const film = f.filmDetail(overrides);
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film)));
  return film;
}

function renderFilm(options: Parameters<typeof renderWithProviders>[1] = {}) {
  return renderWithProviders(<FilmDetailPage />, { path: '/films/:id', route: '/films/12', ...options });
}

const link = (name: string | RegExp) => screen.getByRole('link', { name });

describe('FilmDetailPage', () => {
  it('shows the backdrop, poster, metadata, tagline, overview and cast', async () => {
    const { container } = renderFilm();
    expect(await screen.findByRole('heading', { level: 1, name: 'Dune' })).toBeInTheDocument();
    expect(screen.getByText('2021 · 2 h 35 min · Ciencia ficción, Aventura · 7,8')).toBeInTheDocument();
    expect(screen.getByText('Más allá del miedo, el destino aguarda.')).toBeInTheDocument();
    expect(screen.getByText('Paul Atreides, un joven brillante y con talento…')).toBeInTheDocument();
    expect(container.querySelector('img[src^="/api/artwork/w1280/dune-backdrop.jpg"]')).not.toBeNull();
    expect(container.querySelector('img[src^="/api/artwork/w342/dune-poster.jpg"]')).not.toBeNull();
    const cast = screen.getByRole('region', { name: 'Reparto' });
    expect(within(cast).getByText('Timothée Chalamet')).toBeInTheDocument();
    expect(within(cast).getByText('Paul Atreides')).toBeInTheDocument();
    expect(document.title).toBe('Dune (2021) · HomeTV');
  });

  it('without progress: a single Reproducir with ?file= and no version picker', async () => {
    renderFilm();
    expect(await screen.findByRole('link', { name: 'Reproducir' })).toHaveAttribute('href', '/watch/film/12?file=91');
    expect(screen.queryByRole('link', { name: 'Ver desde el principio' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Versión')).not.toBeInTheDocument();
  });

  it('with resumable progress: Reanudar (no t) + Ver desde el principio (t=0) and the progress bar', async () => {
    serveFilm({ progress: f.progress({ media_file_id: 91, position_seconds: 4325, duration_seconds: 9360 }) });
    renderFilm();
    expect(await screen.findByRole('link', { name: 'Reanudar desde 1:12:05' })).toHaveAttribute('href', '/watch/film/12?file=91');
    expect(link('Ver desde el principio')).toHaveAttribute('href', '/watch/film/12?file=91&t=0');
    expect(screen.getByRole('progressbar', { name: '46 % visto' })).toBeInTheDocument();
  });

  it('a watched film or a position below 30 s shows Reproducir', async () => {
    serveFilm({ progress: f.progress({ media_file_id: 91, position_seconds: 4325, watched: true }) });
    const { unmount } = renderFilm();
    expect(await screen.findByRole('link', { name: 'Reproducir' })).toBeInTheDocument();
    unmount();
    serveFilm({ progress: f.progress({ media_file_id: 91, position_seconds: 20 }) });
    renderFilm();
    expect(await screen.findByRole('link', { name: 'Reproducir' })).toBeInTheDocument();
  });

  describe('versions (capability-aware default, D11)', () => {
    const hevcFirst: MediaFile[] = [
      f.mediaFile({ id: 91, video: HEVC, label: '2160p HEVC HDR10 · EAC3 5.1', size_bytes: 58_100_000_000, duration_seconds: 9360 }),
      f.mediaFile({ id: 92, label: '1080p H264 · AAC 2.0', size_bytes: 23_400_000_000, duration_seconds: 9350 }),
      f.mediaFile({ id: 93, label: '720p H264 · AAC 2.0', size_bytes: 4_000_000_000, duration_seconds: 8000 }),
      f.mediaFile({ id: 94, label: 'Desconocido', size_bytes: 1_000_000_000, probe_error: 'moov atom not found' }),
    ];

    it('lists the versions with suffixes and defaults to the first playable one (HEVC skipped)', async () => {
      serveFilm({ media_files: hevcFirst });
      renderFilm();
      const picker = await screen.findByLabelText('Versión');
      expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual([
        '2160p HEVC HDR10 · EAC3 5.1 · 58,1 GB (puede que no se reproduzca en este navegador)',
        '1080p H264 · AAC 2.0 · 23,4 GB',
        '720p H264 · AAC 2.0 · 4 GB',
        'Desconocido · 1 GB (ilegible)',
      ]);
      expect(picker).toHaveValue('92');
      expect(link('Reproducir')).toHaveAttribute('href', '/watch/film/12?file=92');
    });

    it('resumes on a matching sibling when the last-played version is not in caps', async () => {
      serveFilm({ media_files: hevcFirst, progress: f.progress({ media_file_id: 91, position_seconds: 4325, duration_seconds: 9360 }) });
      renderFilm();
      expect(await screen.findByRole('link', { name: 'Reanudar desde 1:12:05' })).toHaveAttribute('href', '/watch/film/12?file=92');
      expect(link('Ver desde el principio')).toHaveAttribute('href', '/watch/film/12?file=92&t=0');
    });

    it('the label follows the picker: non-matching → Reproducir, last-played → Reanudar', async () => {
      serveFilm({ media_files: hevcFirst, progress: f.progress({ media_file_id: 91, position_seconds: 4325, duration_seconds: 9360 }) });
      const { user } = renderFilm();
      const picker = await screen.findByLabelText('Versión');
      await user.selectOptions(picker, '93');
      expect(link('Reproducir')).toHaveAttribute('href', '/watch/film/12?file=93');
      expect(screen.queryByRole('link', { name: 'Ver desde el principio' })).not.toBeInTheDocument();
      await user.selectOptions(picker, '91');
      expect(link('Reanudar desde 1:12:05')).toHaveAttribute('href', '/watch/film/12?file=91');
    });

    it('shows Reproducir when no version matches the last-played duration', async () => {
      const files = [hevcFirst[0]!, { ...hevcFirst[1]!, duration_seconds: 8000 }];
      serveFilm({ media_files: files, progress: f.progress({ media_file_id: 91, position_seconds: 4325, duration_seconds: 9360 }) });
      renderFilm();
      expect(await screen.findByRole('link', { name: 'Reproducir' })).toHaveAttribute('href', '/watch/film/12?file=92');
    });
  });

  describe('multi-part films', () => {
    const parts: MediaFile[] = [
      f.mediaFile({ id: 201, part_number: 1, label: '1080p H264 · AAC 2.0', size_bytes: 2_100_000_000, duration_seconds: 3000 }),
      f.mediaFile({ id: 202, part_number: 2, label: '1080p H264 · AAC 2.0', size_bytes: 2_000_000_000, duration_seconds: 3100 }),
    ];

    it('labels the versions "Parte N · …" and defaults to Part 1', async () => {
      serveFilm({ media_files: parts });
      renderFilm();
      const picker = await screen.findByLabelText('Versión');
      expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Parte 1 · 1080p H264 · AAC 2.0 · 2,1 GB',
        'Parte 2 · 1080p H264 · AAC 2.0 · 2 GB',
      ]);
      expect(link('Reproducir parte 1')).toHaveAttribute('href', '/watch/film/12?file=201');
    });

    it('after a finished Part 1: "Reproducir parte 2" from 0', async () => {
      serveFilm({ media_files: parts, progress: f.progress({ media_file_id: 201, position_seconds: 2985, duration_seconds: 3000 }) });
      renderFilm();
      expect(await screen.findByRole('link', { name: 'Reproducir parte 2' })).toHaveAttribute('href', '/watch/film/12?file=202');
      expect(screen.getByLabelText('Versión')).toHaveValue('202');
    });

    it('within Part 2: "Reanudar parte 2 desde 0:12:05"', async () => {
      serveFilm({ media_files: parts, progress: f.progress({ media_file_id: 202, position_seconds: 725, duration_seconds: 3100 }) });
      renderFilm();
      expect(await screen.findByRole('link', { name: 'Reanudar parte 2 desde 0:12:05' })).toHaveAttribute(
        'href',
        '/watch/film/12?file=202',
      );
    });

    it('names no part when the default is a standalone version', async () => {
      serveFilm({ media_files: [f.mediaFile({ id: 200, duration_seconds: 6100 }), ...parts] });
      renderFilm();
      expect(await screen.findByRole('link', { name: 'Reproducir' })).toHaveAttribute('href', '/watch/film/12?file=200');
    });
  });

  it('without media files: Reproducir is disabled with a hint', async () => {
    serveFilm({ media_files: [] });
    renderFilm();
    expect(await screen.findByRole('button', { name: 'Reproducir' })).toBeDisabled();
    expect(screen.getByText('No hay ningún archivo reproducible')).toBeInTheDocument();
  });

  describe('watched toggle', () => {
    it('updates optimistically and sends POST /api/watched', async () => {
      server.use(
        http.post(`${API}/watched`, async () => {
          await delay(100);
          return HttpResponse.json({ updated: 1 });
        }),
      );
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Marcar como visto' }));
      expect(screen.getByRole('button', { name: 'Marcar como no visto' })).toHaveAttribute('aria-pressed', 'true');
      await rec.settled();
      expect(rec.find('POST', '/api/watched')[0]?.body).toEqual({ target: 'film', id: 12, watched: true });
      rec.dispose();
    });

    it('rolls back on a 500 and shows a toast', async () => {
      server.use(
        http.post(`${API}/watched`, async () => {
          await delay(50);
          return apiError(500, 'internal_error');
        }),
      );
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Marcar como visto' }));
      expect(screen.getByRole('button', { name: 'Marcar como no visto' })).toBeInTheDocument();
      expect(await screen.findByText('Se ha producido un error inesperado (internal_error).')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Marcar como visto' })).toHaveAttribute('aria-pressed', 'false');
    });
  });

  describe('Mi lista toggle', () => {
    const NOT_AVAILABLE =
      'Esta película no está disponible. Puede que se haya eliminado o que su archivo esté en un disco desconectado.';

    /**
     * A stateful list on the server: GET /api/films/:id reports membership, PUT/DELETE change it once
     * `release()` is called (the write is held open until then).
     */
    function serveWatchlist(initial: boolean) {
      const state = { inList: initial };
      let release!: () => void;
      const gate = new Promise<void>((resolve) => (release = resolve));
      const write = (next: boolean) => async () => {
        await gate;
        state.inList = next;
        return new HttpResponse(null, { status: 204 });
      };
      server.use(
        http.get(`${API}/films/:id`, ({ params }) => HttpResponse.json(f.filmDetail({ id: Number(params.id), in_watchlist: state.inList }))),
        http.put(`${API}/watchlist/films/:id`, write(true)),
        http.delete(`${API}/watchlist/films/:id`, write(false)),
      );
      return release;
    }

    it('sits in the secondary row right after the watched toggle, also without a playable file', async () => {
      serveFilm({ media_files: [] });
      renderFilm();
      const watched = await screen.findByRole('button', { name: 'Marcar como visto' });
      const toggle = screen.getByRole('button', { name: 'Añadir a Mi lista' });
      expect(watched.nextElementSibling).toBe(toggle);
      expect(toggle).toHaveTextContent('+Mi lista');
      expect(toggle).toHaveAttribute('aria-pressed', 'false');
    });

    it('flips before the response, is disabled while pending, then PUTs, refetches the detail, no toast', async () => {
      const release = serveWatchlist(false);
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Añadir a Mi lista' }));
      const toggle = await screen.findByRole('button', { name: 'Quitar de Mi lista' });
      expect(toggle).toHaveTextContent('✓En Mi lista');
      expect(toggle).toHaveAttribute('aria-pressed', 'true');
      expect(toggle).toBeDisabled();
      const detailGets = rec.find('GET', '/api/films/12').length;
      release();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Quitar de Mi lista' })).toBeEnabled());
      await waitFor(() => expect(rec.find('GET', '/api/films/12').length).toBeGreaterThan(detailGets));
      expect(rec.find('PUT', '/api/watchlist/films/12')).toHaveLength(1);
      expect(screen.getByRole('status', { name: 'Notificaciones' })).toBeEmptyDOMElement();
      rec.dispose();
    });

    it('stays disabled after leaving and re-entering the page while the write is in flight', async () => {
      const release = serveWatchlist(false);
      const rec = recordRequests();
      const { user, router } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Añadir a Mi lista' }));
      expect(await screen.findByRole('button', { name: 'Quitar de Mi lista' })).toBeDisabled();
      await act(() => router.navigate('/films'));
      expect(screen.queryByRole('button', { name: /Mi lista/ })).not.toBeInTheDocument();
      await act(() => router.navigate('/films/12'));
      expect(await screen.findByRole('button', { name: 'Quitar de Mi lista' })).toBeDisabled();
      release();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Quitar de Mi lista' })).toBeEnabled());
      expect(rec.find('PUT', '/api/watchlist/films/12')).toHaveLength(1);
      rec.dispose();
    });

    it('sends DELETE when the film is already in the list', async () => {
      const release = serveWatchlist(true);
      const rec = recordRequests();
      const { user } = renderFilm();
      const toggle = await screen.findByRole('button', { name: 'Quitar de Mi lista' });
      expect(toggle).toHaveTextContent('✓En Mi lista');
      await user.click(toggle);
      expect(await screen.findByRole('button', { name: 'Añadir a Mi lista' })).toHaveAttribute('aria-pressed', 'false');
      release();
      await waitFor(() => expect(rec.find('DELETE', '/api/watchlist/films/12')).toHaveLength(1));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Añadir a Mi lista' })).toBeEnabled());
      expect(rec.find('PUT', /\/api\/watchlist\//)).toHaveLength(0);
      rec.dispose();
    });

    it('rolls back on a 500 and shows the error toast', async () => {
      server.use(
        http.put(`${API}/watchlist/films/:id`, async () => {
          await delay(50);
          return apiError(500, 'internal_error');
        }),
      );
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Añadir a Mi lista' }));
      expect(await screen.findByRole('button', { name: 'Quitar de Mi lista' })).toBeInTheDocument();
      expect(await screen.findByText('Se ha producido un error inesperado (internal_error).')).toBeInTheDocument();
      const toggle = await screen.findByRole('button', { name: 'Añadir a Mi lista' });
      expect(toggle).toHaveAttribute('aria-pressed', 'false');
      expect(toggle).toHaveTextContent('+Mi lista');
    });

    it('browser offline: rolls back with the connection toast; nothing is sent once back online', async () => {
      server.use(http.put(`${API}/watchlist/films/:id`, () => HttpResponse.error()));
      const rec = recordRequests();
      const { user } = renderFilm();
      const toggle = await screen.findByRole('button', { name: 'Añadir a Mi lista' });
      onlineManager.setOnline(false);
      try {
        await user.click(toggle);
        expect(await screen.findByText('No se puede conectar con el servidor.')).toBeInTheDocument();
        await waitFor(() => expect(screen.getByRole('button', { name: 'Añadir a Mi lista' })).toBeEnabled());
        expect(screen.getByRole('button', { name: 'Añadir a Mi lista' })).toHaveAttribute('aria-pressed', 'false');
        const puts = rec.find('PUT', '/api/watchlist/films/12').length;
        onlineManager.setOnline(true);
        // A paused mutation would resume (and send its PUT) here.
        for (let i = 0; i < 20; i++) await tick();
        expect(rec.find('PUT', '/api/watchlist/films/12')).toHaveLength(puts);
        expect(screen.getByRole('button', { name: 'Añadir a Mi lista' })).toHaveAttribute('aria-pressed', 'false');
      } finally {
        onlineManager.setOnline(true);
        rec.dispose();
      }
    });

    it('a 404 shows the toast, then the settle refetch turns the page into NotAvailable', async () => {
      let gone = false;
      server.use(
        http.put(`${API}/watchlist/films/:id`, () => {
          gone = true;
          return apiError(404, 'not_found', { message: 'Film not found' });
        }),
        http.get(`${API}/films/:id`, ({ params }) =>
          gone ? apiError(404, 'not_found') : HttpResponse.json(f.filmDetail({ id: Number(params.id) })),
        ),
      );
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Añadir a Mi lista' }));
      const toasts = await screen.findByRole('status', { name: 'Notificaciones' });
      expect(
        await within(toasts).findByText('Este contenido no está disponible. Puede que se haya eliminado o que su disco esté desconectado.'),
      ).toBeInTheDocument();
      expect(await screen.findByText(NOT_AVAILABLE)).toBeInTheDocument();
    });
  });

  it('404: says the film may be on a disconnected drive, with Volver a Películas', async () => {
    server.use(http.get(`${API}/films/:id`, () => apiError(404, 'not_found')));
    const { user } = renderFilm();
    expect(
      await screen.findByText(
        'Esta película no está disponible. Puede que se haya eliminado o que su archivo esté en un disco desconectado.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Volver a Películas' }));
    expect(await findProbedLocation()).toBe('/films');
  });

  it('other errors: ErrorState with Reintentar', async () => {
    let calls = 0;
    server.use(http.get(`${API}/films/:id`, () => (++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.filmDetail()))));
    const { user } = renderFilm();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Dune' })).toBeInTheDocument();
  });

  describe('Corregir identificación', () => {
    it('is not rendered for viewers', async () => {
      renderFilm();
      await screen.findByRole('heading', { level: 1, name: 'Dune' });
      expect(screen.queryByRole('button', { name: 'Corregir identificación' })).not.toBeInTheDocument();
    });

    it('asks admins for the PIN, then opens the fix-match dialog for this film', async () => {
      const rec = recordRequests();
      const { user } = renderFilm({ profile: f.adminProfile() });
      await user.click(await screen.findByRole('button', { name: 'Corregir identificación' }));
      const keypad = await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      for (const digit of ['1', '2', '3', '4']) await user.click(within(keypad).getByRole('button', { name: digit }));
      expect(await screen.findByRole('dialog', { name: 'Corregir identificación: Dune' })).toBeInTheDocument();
      await waitFor(() => expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1));
      rec.dispose();
    });

    it('applies through the real dialog: POST films/{id}/match, toast, and stays when the id is unchanged', async () => {
      const rec = recordRequests();
      const { user, router } = renderFilm({ profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
      await user.click(await screen.findByRole('button', { name: 'Corregir identificación' }));
      const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: Dune' });
      expect(within(dialog).getByRole('textbox', { name: 'Título' })).toHaveValue('Dune');
      await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
      await user.click(await within(dialog).findByRole('radio', { name: 'Dune (2021)' }));
      await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('POST', '/api/admin/films/12/match')[0]!.body).toEqual({ tmdb_id: 438631 });
      expect(screen.getByRole('status', { name: 'Notificaciones' })).toHaveTextContent('Identificado como Dune (2021)');
      expect(currentUrl(router)).toBe('/films/12');
    });

    it('a merge returning another id redirects (replace) to the survivor', async () => {
      server.use(http.post(`${API}/admin/films/:id/match`, () => HttpResponse.json(f.filmDetail({ id: 99 }))));
      const { user, router } = renderFilm({ profile: f.adminProfile(), adminUnlock: f.unlockResponse(), history: ['/films'] });
      await user.click(await screen.findByRole('button', { name: 'Corregir identificación' }));
      const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: Dune' });
      await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
      await user.click(await within(dialog).findByRole('radio', { name: 'Dune (2021)' }));
      await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/films/99'));
      expect(router.state.historyAction).toBe('REPLACE');
    });
  });
});
