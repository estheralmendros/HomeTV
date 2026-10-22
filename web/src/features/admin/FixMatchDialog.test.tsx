import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { createTestQueryClient, findProbedLocation, renderWithProviders, type ProviderOptions } from '../../../tests/renderWithProviders';
import { entryToDialogMode } from './entryToDialogMode';
import { FixMatchDialog } from './FixMatchDialog';
import type { FixMatchMode } from './fixMatchTypes';

const FILM: FixMatchMode = { kind: 'film', id: 12, title: 'Dune', year: 2021 };
const SHOW: FixMatchMode = { kind: 'show', id: 7, title: 'The Office', year: null };
const MULTI_PART = entryToDialogMode(f.unmatchedFileEntry());
const EPISODE_FILE = entryToDialogMode(
  f.unmatchedFileEntry({
    id: 9,
    library: 'shows',
    reason: 'no_tmdb_results',
    target: { type: 'media_file', id: 501, relative_path: 'The Office/The.Office.S02E03.mkv' },
    parsed: { kind: 'episode', show_title: 'The Office', show_year: null, season: 2, episodes: [3] },
  }),
);

const KILL_BILL = f.tmdbSearchResult({ tmdb_id: 24, title: 'Kill Bill: Vol. 1', original_title: 'Kill Bill: Vol. 1', year: 2003 });
const THE_OFFICE = f.tmdbSearchResult({ tmdb_id: 2316, title: 'The Office', original_title: 'The Office', year: 2005, overview: 'Un documental…' });
const THE_OFFICE_UK = f.tmdbSearchResult({ tmdb_id: 2996, title: 'The Office', original_title: 'The Office (UK)', year: 2001 });

function serveSearch(...results: ReturnType<typeof f.tmdbSearchResult>[]) {
  server.use(http.get(`${API}/admin/tmdb/search`, () => HttpResponse.json({ results })));
}

function renderDialog(mode: FixMatchMode, options: ProviderOptions = {}) {
  const onClose = vi.fn();
  const onMatched = vi.fn();
  const calls: string[] = [];
  onClose.mockImplementation(() => calls.push('close'));
  onMatched.mockImplementation(() => calls.push('matched'));
  const result = renderWithProviders(<FixMatchDialog mode={mode} onClose={onClose} onMatched={onMatched} />, {
    path: '/',
    route: '/',
    profile: f.adminProfile(),
    adminUnlock: f.unlockResponse(),
    ...options,
  });
  return { ...result, onClose, onMatched, calls };
}

const dialog = () => screen.getByRole('dialog');
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

describe('FixMatchDialog', () => {
  it('film mode: prefilled search → TMDB proxy → select → Aplicar → POST /api/admin/films/{id}/match', async () => {
    const rec = recordRequests();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.home(), f.homeResponse());
    queryClient.setQueryData(queryKeys.admin.unmatched({ page: 1 }), f.page([]));
    const { user, onMatched, calls } = renderDialog(FILM, { queryClient });

    expect(screen.getByRole('dialog', { name: 'Corregir identificación: Dune' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Tipo' })).toHaveValue('movie');
    expect(screen.getByRole('textbox', { name: 'Título' })).toHaveValue('Dune');
    expect(screen.getByRole('textbox', { name: 'Año' })).toHaveValue('2021');
    expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled();
    expect(rec.find('GET', '/api/admin/tmdb/search')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    const result = await screen.findByRole('radio', { name: 'Dune (2021)' });
    const search = rec.find('GET', '/api/admin/tmdb/search')[0]!;
    expect(Object.fromEntries(search.url.searchParams)).toEqual({ type: 'movie', q: 'Dune', year: '2021' });
    expect(search.headers.get('x-admin-token')).toBe('admin-token-1');
    expect(within(dialog()).getByText('Paul Atreides, un joven brillante…')).toBeInTheDocument();

    await user.click(result);
    await user.click(screen.getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(calls).toEqual(['matched', 'close']));
    await rec.settled();
    const post = rec.find('POST', '/api/admin/films/12/match');
    expect(post).toHaveLength(1);
    expect(post[0]!.body).toEqual({ tmdb_id: 438631 });
    expect(post[0]!.headers.get('x-admin-token')).toBe('admin-token-1');
    expect(onMatched).toHaveBeenCalledWith({ type: 'film', id: 12 });
    expect(toastRegion()).toHaveTextContent('Identificado como Dune (2021)');
    expect(queryClient.getQueryState(queryKeys.home())?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKeys.admin.unmatched({ page: 1 }))?.isInvalidated).toBe(true);
  });

  it('shows original_title when different, omits an empty year, and searches with the edited form', async () => {
    serveSearch(THE_OFFICE, THE_OFFICE_UK);
    const rec = recordRequests();
    const { user } = renderDialog(SHOW);
    expect(screen.getByRole('combobox', { name: 'Tipo' })).toHaveValue('tv');
    expect(screen.getByRole('textbox', { name: 'Año' })).toHaveValue('');
    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    await screen.findByRole('radio', { name: 'The Office (2001)' });
    expect(within(dialog()).getByText('The Office (UK)')).toBeInTheDocument();
    expect(Object.fromEntries(rec.find('GET', '/api/admin/tmdb/search')[0]!.url.searchParams)).toEqual({ type: 'tv', q: 'The Office' });

    await user.clear(screen.getByRole('textbox', { name: 'Título' }));
    await user.type(screen.getByRole('textbox', { name: 'Título' }), 'Office');
    await user.type(screen.getByRole('textbox', { name: 'Año' }), '2005');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Tipo' }), 'Película');
    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    await waitFor(() => expect(rec.find('GET', '/api/admin/tmdb/search')).toHaveLength(2));
    expect(Object.fromEntries(rec.find('GET', '/api/admin/tmdb/search')[1]!.url.searchParams)).toEqual({
      type: 'movie',
      q: 'Office',
      year: '2005',
    });
  });

  it('show mode → POST /api/admin/shows/{id}/match; a merge returns the survivor id to onMatched', async () => {
    serveSearch(THE_OFFICE);
    server.use(http.post(`${API}/admin/shows/:id/match`, () => HttpResponse.json(f.showDetail({ id: 9, year: 2005 }))));
    const rec = recordRequests();
    const { user, onMatched, calls } = renderDialog(SHOW);
    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    await user.click(await screen.findByRole('radio', { name: 'The Office (2005)' }));
    await user.click(screen.getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(calls).toEqual(['matched', 'close']));
    await rec.settled();
    expect(rec.find('POST', '/api/admin/shows/7/match')[0]!.body).toEqual({ tmdb_id: 2316 });
    expect(onMatched).toHaveBeenCalledWith({ type: 'show', id: 9 });
    expect(rec.find('POST', /resolve/)).toHaveLength(0);
  });

  it('films-library file entry → POST resolve {tmdb_id}; multi_part toast mentions the other parts; "Abrir" opens the film', async () => {
    serveSearch(KILL_BILL);
    server.use(http.post(`${API}/admin/unmatched/:id/resolve`, () => HttpResponse.json({ target: { type: 'film', id: 30 } })));
    const rec = recordRequests();
    const { user, onMatched, calls } = renderDialog(MULTI_PART);
    expect(screen.getByRole('dialog', { name: 'Corregir identificación: Kill.Bill.Vol.1.2003.cd1.avi' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Título' })).toHaveValue('Kill Bill Vol 1');
    expect(screen.getByRole('textbox', { name: 'Año' })).toHaveValue('2003');
    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    await user.click(await screen.findByRole('radio', { name: 'Kill Bill: Vol. 1 (2003)' }));
    expect(screen.queryByRole('combobox', { name: 'Temporada' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(calls).toEqual(['matched', 'close']));
    await rec.settled();
    expect(rec.find('POST', '/api/admin/unmatched/6/resolve')[0]!.body).toEqual({ tmdb_id: 24 });
    expect(onMatched).toHaveBeenCalledWith({ type: 'film', id: 30 });
    expect(toastRegion()).toHaveTextContent(
      'Identificado como Kill Bill: Vol. 1 (2003). También se han vinculado las otras partes de esta película.',
    );
    await user.click(within(toastRegion()).getByRole('button', { name: 'Abrir' }));
    expect(await findProbedLocation()).toBe('/films/30');
  });

  describe('shows-library file entry', () => {
    it('Temporada / Episodios dropdowns load from the TMDB season endpoints, prefilled from parsed', async () => {
      serveSearch(THE_OFFICE);
      server.use(http.post(`${API}/admin/unmatched/:id/resolve`, () => HttpResponse.json({ target: { type: 'show', id: 7 } })));
      const rec = recordRequests();
      const { user, onMatched, calls } = renderDialog(EPISODE_FILE);
      expect(screen.getByRole('combobox', { name: 'Tipo' })).toHaveValue('tv');
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'The Office (2005)' }));

      const season = await screen.findByRole('combobox', { name: 'Temporada' });
      await waitFor(() => expect(season).toHaveDisplayValue('Temporada 2 · 22 episodios'));
      expect(within(season).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Temporada 1 · 6 episodios',
        'Temporada 2 · 22 episodios',
        'Especiales · 30 episodios',
      ]);
      const episodes = await screen.findByRole('listbox', { name: 'Episodios' });
      await waitFor(() => expect(episodes).toHaveDisplayValue(['E3 · Office Olympics · 4 oct 2005']));
      expect(within(episodes).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'E1 · The Dundies · 20 sept 2005',
        'E3 · Office Olympics · 4 oct 2005',
      ]);
      expect(rec.find('GET', '/api/admin/tmdb/tv/2316/seasons')).toHaveLength(1);
      expect(rec.find('GET', '/api/admin/tmdb/tv/2316/seasons/2')).toHaveLength(1);

      await user.selectOptions(episodes, ['E1 · The Dundies · 20 sept 2005']);
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      await waitFor(() => expect(calls).toEqual(['matched', 'close']));
      await rec.settled();
      expect(rec.find('POST', '/api/admin/unmatched/9/resolve')[0]!.body).toEqual({
        tmdb_id: 2316,
        season_number: 2,
        episode_numbers: [1, 3],
      });
      expect(onMatched).toHaveBeenCalledWith({ type: 'show', id: 7 });
      expect(toastRegion()).toHaveTextContent('Identificado como The Office (2005)');
    });

    it('a parsed season missing from TMDB → the first season and no episode; Aplicar needs an episode', async () => {
      serveSearch(THE_OFFICE);
      const mode = entryToDialogMode(
        f.unmatchedFileEntry({
          id: 9,
          library: 'shows',
          reason: 'no_tmdb_results',
          target: { type: 'media_file', id: 501, relative_path: 'The Office/The.Office.S07E03.mkv' },
          parsed: { kind: 'episode', show_title: 'The Office', show_year: null, season: 7, episodes: [3] },
        }),
      );
      const rec = recordRequests();
      const { user } = renderDialog(mode);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'The Office (2005)' }));
      const season = await screen.findByRole('combobox', { name: 'Temporada' });
      await waitFor(() => expect(season).toHaveDisplayValue('Temporada 1 · 6 episodios'));
      const episodes = await screen.findByRole('listbox', { name: 'Episodios' });
      await waitFor(() => expect(within(episodes).getAllByRole('option')).toHaveLength(2));
      expect(episodes).toHaveDisplayValue([]);
      expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled();

      await user.selectOptions(season, 'Temporada 2 · 22 episodios');
      await waitFor(() => expect(rec.find('GET', '/api/admin/tmdb/tv/2316/seasons/2')).toHaveLength(1));
      await user.selectOptions(await screen.findByRole('listbox', { name: 'Episodios' }), 'E3 · Office Olympics · 4 oct 2005');
      expect(screen.getByRole('button', { name: 'Aplicar' })).toBeEnabled();
    });

    it('a lookup failure shows the error and falls back to manual Temporada / Episodios inputs (parseEpisodeList)', async () => {
      serveSearch(THE_OFFICE);
      server.use(http.get(`${API}/admin/tmdb/tv/:id/seasons`, () => apiError(502, 'tmdb_unavailable')));
      const rec = recordRequests();
      const { user, calls } = renderDialog(EPISODE_FILE);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'The Office (2005)' }));
      expect(await within(dialog()).findByText('TMDB no responde. Vuelve a intentarlo más tarde.')).toBeInTheDocument();
      const season = screen.getByRole('spinbutton', { name: 'Temporada' });
      const episodes = screen.getByRole('textbox', { name: 'Episodios' });
      expect(season).toHaveValue(2);
      expect(episodes).toHaveValue('3');

      await user.clear(episodes);
      await user.type(episodes, 'abc');
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      expect(within(dialog()).getByText('Introduce los episodios, por ejemplo 3 o 16-17.')).toBeInTheDocument();
      expect(rec.find('POST', /resolve/)).toHaveLength(0);

      await user.clear(episodes);
      await user.type(episodes, '16-17');
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      await waitFor(() => expect(calls).toEqual(['matched', 'close']));
      await rec.settled();
      expect(rec.find('POST', '/api/admin/unmatched/9/resolve')[0]!.body).toEqual({
        tmdb_id: 2316,
        season_number: 2,
        episode_numbers: [16, 17],
      });
    });

    it('a 404 tmdb_not_found on the season episodes also falls back', async () => {
      serveSearch(THE_OFFICE);
      server.use(http.get(`${API}/admin/tmdb/tv/:id/seasons/:n`, () => apiError(404, 'tmdb_not_found')));
      const { user } = renderDialog(EPISODE_FILE);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'The Office (2005)' }));
      expect(await within(dialog()).findByText('Esa entrada de TMDB ya no existe.')).toBeInTheDocument();
      expect(screen.getByRole('spinbutton', { name: 'Temporada' })).toHaveValue(2);
      expect(screen.getByRole('textbox', { name: 'Episodios' })).toHaveValue('3');
    });
  });

  describe('errors stay inside the dialog', () => {
    it('no results', async () => {
      serveSearch();
      const { user } = renderDialog(FILM);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      expect(await within(dialog()).findByText('No hay resultados en TMDB. Prueba con otro título o quita el año.')).toBeInTheDocument();
    });

    it('502 tmdb_unavailable on search', async () => {
      server.use(http.get(`${API}/admin/tmdb/search`, () => apiError(502, 'tmdb_unavailable')));
      const { user, onClose } = renderDialog(FILM);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      expect(await within(dialog()).findByText('TMDB no responde. Vuelve a intentarlo más tarde.')).toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();
    });

    it('503 tmdb_not_configured on apply', async () => {
      server.use(http.post(`${API}/admin/films/:id/match`, () => apiError(503, 'tmdb_not_configured')));
      const { user, onClose, onMatched } = renderDialog(FILM);
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'Dune (2021)' }));
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      expect(
        await within(dialog()).findByText('No hay ninguna clave de API de TMDB configurada en el servidor.'),
      ).toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();
      expect(onMatched).not.toHaveBeenCalled();
    });

    it('the search form is disabled when TMDB is not configured', async () => {
      const { user } = renderDialog(FILM, { info: f.info({ tmdb_configured: false }) });
      expect(within(dialog()).getByText('No hay ninguna clave de API de TMDB configurada en el servidor.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Buscar' })).toBeDisabled();
      expect(screen.getByRole('textbox', { name: 'Título' })).toBeDisabled();
      await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    });

    it('409 not_resolvable: the message, Aplicar disabled and the unmatched list refetched', async () => {
      serveSearch(KILL_BILL);
      server.use(http.post(`${API}/admin/unmatched/:id/resolve`, () => apiError(409, 'not_resolvable')));
      const queryClient = createTestQueryClient();
      queryClient.setQueryData(queryKeys.admin.unmatched({ page: 1 }), f.page([]));
      const { user, onClose } = renderDialog(MULTI_PART, { queryClient });
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'Kill Bill: Vol. 1 (2003)' }));
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      expect(
        await within(dialog()).findByText(
          'Este archivo no se puede leer, así que no se puede identificar. Repara o sustituye el archivo y escanea de nuevo.',
        ),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled();
      expect(queryClient.getQueryState(queryKeys.admin.unmatched({ page: 1 }))?.isInvalidated).toBe(true);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('409 not_a_file_entry: the message and the list refetched', async () => {
      serveSearch(KILL_BILL);
      server.use(http.post(`${API}/admin/unmatched/:id/resolve`, () => apiError(409, 'not_a_file_entry')));
      const queryClient = createTestQueryClient();
      queryClient.setQueryData(queryKeys.admin.unmatched({ page: 1 }), f.page([]));
      const { user } = renderDialog(MULTI_PART, { queryClient });
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'Kill Bill: Vol. 1 (2003)' }));
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      expect(await within(dialog()).findByText('Esta entrada se tiene que corregir desde su película o serie.')).toBeInTheDocument();
      expect(queryClient.getQueryState(queryKeys.admin.unmatched({ page: 1 }))?.isInvalidated).toBe(true);
    });

    it('404 not_found on resolve (already resolved as a sibling): close, toast "Ya estaba identificado.", refetch', async () => {
      serveSearch(KILL_BILL);
      server.use(http.post(`${API}/admin/unmatched/:id/resolve`, () => apiError(404, 'not_found')));
      const queryClient = createTestQueryClient();
      queryClient.setQueryData(queryKeys.admin.unmatched({ page: 1 }), f.page([]));
      const { user, onClose, onMatched } = renderDialog(MULTI_PART, { queryClient });
      await user.click(screen.getByRole('button', { name: 'Buscar' }));
      await user.click(await screen.findByRole('radio', { name: 'Kill Bill: Vol. 1 (2003)' }));
      await user.click(screen.getByRole('button', { name: 'Aplicar' }));
      await waitFor(() => expect(onClose).toHaveBeenCalled());
      expect(onMatched).not.toHaveBeenCalled();
      expect(toastRegion()).toHaveTextContent('Ya estaba identificado.');
      expect(queryClient.getQueryState(queryKeys.admin.unmatched({ page: 1 }))?.isInvalidated).toBe(true);
    });

    it('Cancelar closes without any request', async () => {
      const rec = recordRequests();
      const { user, onClose } = renderDialog(FILM);
      await user.click(screen.getByRole('button', { name: 'Cancelar' }));
      expect(onClose).toHaveBeenCalledTimes(1);
      expect(rec.calls).toHaveLength(0);
    });
  });

  it('Aplicar while locked asks for the PIN first (useAdminAction), then applies', async () => {
    const rec = recordRequests();
    const { user, calls } = renderDialog(FILM);
    await user.click(screen.getByRole('button', { name: 'Buscar' }));
    await user.click(await screen.findByRole('radio', { name: 'Dune (2021)' }));
    const { adminUnlock } = await import('@/auth/adminUnlock');
    adminUnlock.clear();
    await user.click(screen.getByRole('button', { name: 'Aplicar' }));
    await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
    await user.keyboard('1234');
    await waitFor(() => expect(calls).toEqual(['matched', 'close']));
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
    expect(rec.find('POST', '/api/admin/films/12/match')).toHaveLength(1);
  });
});
