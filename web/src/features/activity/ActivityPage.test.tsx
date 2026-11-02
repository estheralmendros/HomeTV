// /activity: rows, own rows, links, paging, dedupe, empty,
// error, refetch on every visit.
import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ActivityEntry } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp } from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';

/** Local wall-clock time as the server's ISO string. */
const local = (d: number, h: number, min = 0) => new Date(2026, 9, d, h, min).toISOString();

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 31, 12, 0));
});
afterEach(() => vi.useRealTimers());

const luis = { id: 3, name: 'Luis', avatar_id: 'avatar-07' };
const ana = { id: 1, name: 'Ana', avatar_id: 'avatar-05' }; // the signed-in profile (f.profile())

function serveActivity(items: ActivityEntry[], overrides: { total?: number } = {}) {
  server.use(http.get(`${API}/activity`, () => HttpResponse.json(f.page(items, { page_size: 50, ...overrides }))));
}

/** `total` entries by Luis on films 1..n, honouring ?page= / ?page_size=. */
function servePages(total: number) {
  server.use(
    http.get(`${API}/activity`, ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('page_size'));
      const start = (page - 1) * size;
      const n = Math.max(0, Math.min(size, total - start));
      const items = Array.from({ length: n }, (_, i) =>
        f.activityEntry({ profile: luis, item: f.filmSummary({ id: start + i + 1, title: `Film ${start + i + 1}` }), rated_at: local(31, 11, 55) }),
      );
      return HttpResponse.json(f.page(items, { page, page_size: size, total }));
    }),
  );
}

const rows = () => within(screen.getByRole('list', { name: 'Actividad' })).getAllByRole('listitem');

describe('ActivityPage', () => {
  it('h1 "Actividad", document title, rows with avatar, sentence, stars, relative time, poster; links to the detail', async () => {
    serveActivity([
      f.activityEntry({ profile: luis, stars: 2, rated_at: local(31, 11, 55) }),
      f.activityEntry({ profile: ana, kind: 'show', item: f.showSummary(), stars: 5, rated_at: local(30, 18) }),
      f.activityEntry({ profile: luis, item: f.filmSummary({ id: 13, title: 'Alien', year: 1979 }), stars: 3.5, rated_at: local(12, 9) }),
    ]);
    renderApp({ route: '/activity' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Actividad' })).toBeInTheDocument();
    await waitFor(() => expect(rows()).toHaveLength(3));
    expect(document.title).toBe('Actividad · HomeTV');

    const other = screen.getByRole('link', { name: 'Luis ha puntuado Dune con 2 estrellas, hace 5 minutos' });
    expect(other).toHaveAttribute('href', '/films/12');
    expect(other).toHaveTextContent('Luis ha puntuado Dune con');
    expect(within(other).getByRole('img', { name: '2 estrellas' })).toBeInTheDocument();
    expect(within(other).getByText('hace 5 minutos').tagName).toBe('TIME');
    expect(within(other).getByText('hace 5 minutos')).toHaveAttribute('dateTime', local(31, 11, 55));
    expect(other.querySelector('[data-kind="poster"] img')).toHaveAttribute('src', expect.stringContaining('dune-poster'));

    const own = screen.getByRole('link', { name: 'Has puntuado The Office con 5 estrellas, ayer' });
    expect(own).toHaveAttribute('href', '/shows/7');
    expect(own).toHaveTextContent('Has puntuado The Office con');

    expect(screen.getByRole('link', { name: 'Luis ha puntuado Alien con 3,5 estrellas, 12 oct' })).toHaveAttribute('href', '/films/13');
  });

  it('the link names use the formatted stars for own and other rows (never "con 4.5")', async () => {
    serveActivity([
      f.activityEntry({ profile: ana, stars: 4.5, rated_at: local(30, 10) }),
      f.activityEntry({ profile: luis, stars: 0.5, rated_at: local(30, 10) }),
    ]);
    renderApp({ route: '/activity' });
    expect(await screen.findByRole('link', { name: 'Has puntuado Dune con 4,5 estrellas, ayer' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Luis ha puntuado Dune con 0,5 estrellas, ayer' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /4\.5/ })).not.toBeInTheDocument();
  });

  it('dedupes by profile + title (first wins)', async () => {
    serveActivity([
      f.activityEntry({ profile: luis, stars: 2 }),
      f.activityEntry({ profile: luis, stars: 4 }),
      f.activityEntry({ profile: ana, stars: 4 }),
    ]);
    renderApp({ route: '/activity' });
    await waitFor(() => expect(rows()).toHaveLength(2));
    expect(screen.getByRole('link', { name: /^Luis ha puntuado Dune con 2 estrellas/ })).toBeInTheDocument();
  });

  it('pages of 50 with "Cargar más" (LoadMoreFooter)', async () => {
    servePages(120);
    const rec = recordRequests();
    const { user } = renderApp({ route: '/activity' });
    await waitFor(() => expect(rows()).toHaveLength(50));
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(rows()).toHaveLength(100));
    await user.click(screen.getByRole('button', { name: 'Cargar más' }));
    await waitFor(() => expect(rows()).toHaveLength(120));
    expect(screen.queryByRole('button', { name: 'Cargar más' })).not.toBeInTheDocument();
    expect(rec.find('GET', '/api/activity').map((c) => Object.fromEntries(c.url.searchParams))).toEqual([
      { page: '1', page_size: '50' },
      { page: '2', page_size: '50' },
      { page: '3', page_size: '50' },
    ]);
  });

  it('empty: the empty text', async () => {
    renderApp({ route: '/activity' });
    expect(
      await screen.findByText('Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí.'),
    ).toBeInTheDocument();
  });

  it('error without data: ErrorState with Reintentar', async () => {
    server.use(http.get(`${API}/activity`, () => apiError(500, 'internal_error'), { once: true }));
    const { user } = renderApp({ route: '/activity' });
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }));
    expect(
      await screen.findByText('Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí.'),
    ).toBeInTheDocument();
  });

  it('is refetched on every visit', async () => {
    serveActivity([f.activityEntry()]);
    const rec = recordRequests();
    const { router } = renderApp({ route: '/activity' });
    await waitFor(() => expect(rows()).toHaveLength(1));
    await router.navigate('/');
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'Actividad' })).not.toBeInTheDocument());
    await router.navigate('/activity');
    await waitFor(() => expect(rec.find('GET', '/api/activity')).toHaveLength(2));
    expect(currentUrl(router)).toBe('/activity');
  });

  it('an unmatched title (no poster) shows the compact title tile, not the default placeholder', async () => {
    serveActivity([
      f.activityEntry({ profile: ana, kind: 'show', item: f.showSummary({ poster: null }), stars: 5, rated_at: local(31, 11, 55) }),
    ]);
    renderApp({ route: '/activity' });
    const link = await screen.findByRole('link', { name: 'Has puntuado The Office con 5 estrellas, hace 5 minutos' });
    const tile = link.querySelector('[data-kind="poster"]');
    expect(tile).not.toBeNull();
    expect(within(tile as HTMLElement).getByTestId('activity-poster-title')).toHaveTextContent('The Office');
    expect(within(tile as HTMLElement).queryByTestId('artwork-placeholder')).toBeNull();
    expect(tile?.querySelector('img')).toBeNull();
  });

  it('360 px: the row keeps avatar, sentence, time and poster', async () => {
    setViewportWidth(360);
    serveActivity([f.activityEntry({ profile: luis, rated_at: local(31, 11, 55) })]);
    renderApp({ route: '/activity' });
    const link = await screen.findByRole('link', { name: 'Luis ha puntuado Dune con 2 estrellas, hace 5 minutos' });
    expect(within(link).getByText('hace 5 minutos')).toBeInTheDocument();
    expect(link.querySelector('[data-kind="poster"]')).not.toBeNull();
  });
});
