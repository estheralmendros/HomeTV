import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { UnmatchedEntry } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';

function renderUnmatched(route = '/admin/unmatched', options: ProviderOptions = {}) {
  return renderApp({ route, profile: f.adminProfile(), adminUnlock: f.unlockResponse(), ...options });
}

function serveUnmatched(items: UnmatchedEntry[], overrides: { total?: number } = {}) {
  server.use(
    http.get(`${API}/admin/unmatched`, ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? 1);
      return HttpResponse.json(f.page(items, { page, page_size: 50, total: overrides.total ?? items.length }));
    }),
  );
}

const list = () => screen.getByRole('list', { name: 'Sin identificar' });
const rows = () => within(list()).getAllByRole('listitem');
const rowOf = (text: string | RegExp) => rows().find((r) => within(r).queryAllByText(text).length > 0)!;
const query = (call: { url: URL }) => Object.fromEntries(call.url.searchParams);

const PROBE_FAILED = f.unmatchedFileEntry({
  id: 10,
  reason: 'probe_failed',
  detail: 'ffprobe: Invalid data found when processing input',
  target: { type: 'media_file', id: 77, relative_path: 'Broken/broken.mkv' },
  parsed: null,
});
const NO_SEASON = f.unmatchedFileEntry({
  id: 11,
  library: 'shows',
  reason: 'no_season',
  target: { type: 'media_file', id: 78, relative_path: 'One Piece/One Piece - 105.mkv' },
  parsed: null,
});
const MULTI_EPISODE = f.unmatchedFileEntry({
  id: 12,
  library: 'shows',
  reason: 'no_tmdb_results',
  target: { type: 'media_file', id: 79, relative_path: 'Friends/Friends.S01E01E02.mkv' },
  parsed: { kind: 'episode', show_title: 'Friends', show_year: 1994, season: 1, episodes: [1, 2] },
});
const FILM_TARGET = f.unmatchedEntry({
  id: 13,
  library: 'films',
  reason: 'no_tmdb_results',
  detail: null,
  target: { type: 'film', id: 40, title: 'Dune Part 3', year: 2027 },
  parsed: { kind: 'film', title: 'Dune Part 3', year: 2027, part: null },
});

describe('UnmatchedPage', () => {
  it('lists rows with library, human reason, raw detail, target, parsed summary and date', async () => {
    const rec = recordRequests();
    serveUnmatched([f.unmatchedEntry(), f.unmatchedFileEntry(), FILM_TARGET, MULTI_EPISODE, NO_SEASON]);
    renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    expect(rows()).toHaveLength(5);

    const show = rows()[0]!;
    expect(within(show).getByText('Series')).toBeInTheDocument();
    expect(within(show).getByText('Identificación dudosa')).toBeInTheDocument();
    expect(within(show).getByText('2 exact-title candidates: 2316, 2996')).toBeInTheDocument();
    expect(within(show).getByRole('link', { name: 'The Office' })).toHaveAttribute('href', '/shows/7');
    expect(within(show).getByText('The Office · T2 · E3')).toBeInTheDocument();
    expect(within(show).getByText('Añadida el 21 oct 2026')).toBeInTheDocument();

    const part = rows()[1]!;
    expect(within(part).getByText('Películas')).toBeInTheDocument();
    expect(within(part).getByText('Película en varias partes · Parte 1')).toBeInTheDocument();
    expect(within(part).getByText('Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi')).toBeInTheDocument();
    expect(within(part).getByText('Kill Bill Vol 1 (2003) · Parte 1')).toBeInTheDocument();

    expect(within(rows()[2]!).getByRole('link', { name: 'Dune Part 3 (2027)' })).toHaveAttribute('href', '/films/40');
    expect(within(rows()[2]!).getByText('Sin resultados en TMDB')).toBeInTheDocument();
    expect(within(rows()[3]!).getByText('Friends (1994) · T1 · E1–E2')).toBeInTheDocument();
    expect(within(rows()[4]!).getByText('Sin número de temporada')).toBeInTheDocument();
    // parsed null → the file-name stem.
    expect(within(rows()[4]!).getByText('One Piece - 105')).toBeInTheDocument();

    await rec.settled();
    const call = rec.find('GET', '/api/admin/unmatched')[0]!;
    expect(query(call)).toEqual({ page: '1', page_size: '50' });
    expect(call.headers.get('x-admin-token')).toBe('admin-token-1');
  });

  it('filters by library and reason through the URL and the request params', async () => {
    const rec = recordRequests();
    const { user, router } = renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    const library = screen.getByRole('combobox', { name: 'Biblioteca' });
    const reason = screen.getByRole('combobox', { name: 'Motivo' });
    expect(within(library).getAllByRole('option').map((o) => o.textContent)).toEqual(['Todas', 'Películas', 'Series']);
    expect(within(reason).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Todos',
      'No se ha podido leer un título',
      'Sin número de temporada',
      'Sin número de episodio',
      'Película en varias partes',
      'No se ha podido analizar el archivo',
      'Sin resultados en TMDB',
      'Identificación dudosa',
    ]);

    await user.selectOptions(library, 'Series');
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/unmatched?library=shows'));
    await user.selectOptions(reason, 'Sin número de temporada');
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/unmatched?library=shows&reason=no_season'));
    await waitFor(() => expect(rec.find('GET', '/api/admin/unmatched')).toHaveLength(3));
    expect(query(rec.find('GET', '/api/admin/unmatched')[2]!)).toEqual({
      library: 'shows',
      reason: 'no_season',
      page: '1',
      page_size: '50',
    });
    await user.selectOptions(library, 'Todas');
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/unmatched?reason=no_season'));
  });

  it('paginates with Anterior / Siguiente and "Página N de M"; a filter change resets the page', async () => {
    serveUnmatched([f.unmatchedEntry()], { total: 120 });
    const rec = recordRequests();
    const { user, router } = renderUnmatched('/admin/unmatched?library=films&page=2');
    expect(await screen.findByText('Página 2 de 3')).toBeInTheDocument();
    expect(query(rec.find('GET', '/api/admin/unmatched')[0]!)).toEqual({ library: 'films', page: '2', page_size: '50' });
    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    expect(await screen.findByText('Página 3 de 3')).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/admin/unmatched?library=films&page=3');
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByText('Página 1 de 3')).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/admin/unmatched?library=films');
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/unmatched?library=films&page=2'));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Motivo' }), 'Película en varias partes');
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/unmatched?library=films&reason=multi_part'));
  });

  it('Fix is enabled for multi_part and disabled with an explanation for probe_failed', async () => {
    serveUnmatched([f.unmatchedFileEntry(), PROBE_FAILED]);
    renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    expect(within(rowOf('Película en varias partes · Parte 1')).getByRole('button', { name: 'Corregir identificación' })).toBeEnabled();
    const broken = rowOf('No se ha podido analizar el archivo');
    expect(within(broken).getByText('broken')).toBeInTheDocument();
    expect(within(broken).getByText('ffprobe: Invalid data found when processing input')).toBeInTheDocument();
    const fix = within(broken).getByRole('button', { name: 'Corregir identificación' });
    expect(fix).toBeDisabled();
    expect(fix).toHaveAccessibleDescription(
      'El archivo no se puede leer. Repáralo o sustitúyelo; esta entrada desaparecerá en el próximo escaneo correcto.',
    );
  });

  it('a show-target row opens the dialog in match mode (POST /api/admin/shows/{id}/match)', async () => {
    server.use(
      http.get(`${API}/admin/tmdb/search`, () =>
        HttpResponse.json({ results: [f.tmdbSearchResult({ tmdb_id: 2316, title: 'The Office', year: 2005 })] }),
      ),
    );
    const rec = recordRequests();
    const { user } = renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    await user.click(within(rowOf('Identificación dudosa')).getByRole('button', { name: 'Corregir identificación' }));
    const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: The Office' });
    await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
    await user.click(await within(dialog).findByRole('radio', { name: 'The Office (2005)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await rec.settled();
    expect(rec.find('POST', '/api/admin/shows/7/match')[0]!.body).toEqual({ tmdb_id: 2316 });
    expect(rec.find('POST', /resolve/)).toHaveLength(0);
  });

  it('a film-target row uses the film match endpoint', async () => {
    serveUnmatched([FILM_TARGET]);
    const rec = recordRequests();
    const { user } = renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    await user.click(within(rows()[0]!).getByRole('button', { name: 'Corregir identificación' }));
    const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: Dune Part 3' });
    await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
    await user.click(await within(dialog).findByRole('radio', { name: 'Dune (2021)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(rec.find('POST', '/api/admin/films/40/match')).toHaveLength(1));
    expect(rec.find('POST', /resolve/)).toHaveLength(0);
  });

  it('multi_part resolve: the media_file row resolves, the toast mentions the parts and the siblings disappear', async () => {
    const cd2 = f.unmatchedFileEntry({
      id: 7,
      target: { type: 'media_file', id: 92, relative_path: 'Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd2.avi' },
      parsed: { kind: 'film', title: 'Kill Bill Vol 1', year: 2003, part: 2 },
    });
    let resolved = false;
    server.use(
      http.get(`${API}/admin/unmatched`, () =>
        HttpResponse.json(f.page(resolved ? [f.unmatchedEntry()] : [f.unmatchedFileEntry(), cd2, f.unmatchedEntry()], { page_size: 50 })),
      ),
      http.get(`${API}/admin/scan`, () => HttpResponse.json(f.scanStatus({ unmatched_count: resolved ? 1 : 3 }))),
      http.get(`${API}/admin/tmdb/search`, () =>
        HttpResponse.json({ results: [f.tmdbSearchResult({ tmdb_id: 24, title: 'Kill Bill: Vol. 1', year: 2003 })] }),
      ),
      http.post(`${API}/admin/unmatched/:id/resolve`, () => {
        resolved = true;
        return HttpResponse.json({ target: { type: 'film', id: 30 } });
      }),
    );
    const rec = recordRequests();
    const { user } = renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    expect(await screen.findByRole('link', { name: 'Sin identificar 3' })).toBeInTheDocument();
    await user.click(within(rowOf('Película en varias partes · Parte 1')).getByRole('button', { name: 'Corregir identificación' }));
    const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: Kill.Bill.Vol.1.2003.cd1.avi' });
    expect(within(dialog).getByRole('textbox', { name: 'Título' })).toHaveValue('Kill Bill Vol 1');
    await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
    await user.click(await within(dialog).findByRole('radio', { name: 'Kill Bill: Vol. 1 (2003)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));

    expect(
      await screen.findByText(
        'Identificado como Kill Bill: Vol. 1 (2003). También se han vinculado las otras partes de esta película.',
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(rows()).toHaveLength(1));
    expect(screen.queryByText(/Kill\.Bill/)).not.toBeInTheDocument();
    await rec.settled();
    expect(rec.find('POST', '/api/admin/unmatched/6/resolve')[0]!.body).toEqual({ tmdb_id: 24 });
    // The badge follows unmatched_count (['admin','scan'] refetched after the resolve).
    expect(await screen.findByRole('link', { name: 'Sin identificar 1' })).toBeInTheDocument();
  });

  it('a shows media_file row resolves with season and episodes', async () => {
    serveUnmatched([NO_SEASON]);
    server.use(
      http.get(`${API}/admin/tmdb/search`, () =>
        HttpResponse.json({ results: [f.tmdbSearchResult({ tmdb_id: 2316, title: 'The Office', year: 2005 })] }),
      ),
      http.post(`${API}/admin/unmatched/:id/resolve`, () => HttpResponse.json({ target: { type: 'show', id: 7 } })),
    );
    const rec = recordRequests();
    const { user } = renderUnmatched();
    await screen.findByRole('list', { name: 'Sin identificar' });
    await user.click(within(rows()[0]!).getByRole('button', { name: 'Corregir identificación' }));
    const dialog = await screen.findByRole('dialog', { name: 'Corregir identificación: One Piece - 105.mkv' });
    expect(within(dialog).getByRole('textbox', { name: 'Título' })).toHaveValue('One Piece - 105');
    await user.click(within(dialog).getByRole('button', { name: 'Buscar' }));
    await user.click(await within(dialog).findByRole('radio', { name: 'The Office (2005)' }));
    const episodes = await within(dialog).findByRole('listbox', { name: 'Episodios' });
    await waitFor(() => expect(within(episodes).getAllByRole('option')).toHaveLength(2));
    await user.selectOptions(episodes, 'E1 · The Dundies · 20 sept 2005');
    await user.click(within(dialog).getByRole('button', { name: 'Aplicar' }));
    await waitFor(() => expect(rec.find('POST', '/api/admin/unmatched/11/resolve')).toHaveLength(1));
    await rec.settled();
    expect(rec.find('POST', '/api/admin/unmatched/11/resolve')[0]!.body).toEqual({
      tmdb_id: 2316,
      season_number: 1,
      episode_numbers: [1],
    });
  });

  it('empty: "Todo está identificado." and, with filters, "No hay entradas sin identificar con estos filtros."', async () => {
    serveUnmatched([]);
    const { unmount } = renderUnmatched();
    expect(await screen.findByText('Todo está identificado.')).toBeInTheDocument();
    unmount();
    renderUnmatched('/admin/unmatched?reason=multi_part');
    expect(await screen.findByText('No hay entradas sin identificar con estos filtros.')).toBeInTheDocument();
  });

  it('a list error shows ErrorState with Reintentar', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/admin/unmatched`, () =>
        ++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.page([f.unmatchedEntry()], { page_size: 50 })),
      ),
    );
    const { user } = renderUnmatched();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('list', { name: 'Sin identificar' });
  });
});
