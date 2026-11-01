// RatingsBlock on film/show detail (ratings prd2 FR-W10, W-AC-10, W-AC-4).
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { FilmDetail, OtherRating } from '@/api/types';
import { FilmDetailPage } from '@/features/film/FilmDetailPage';
import { ShowDetailPage } from '@/features/show/ShowDetailPage';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { deferred } from '../../../tests/player';
import { renderWithProviders } from '../../../tests/renderWithProviders';

function serveFilm(overrides: Partial<FilmDetail>) {
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail(overrides))));
}

function renderFilm() {
  return renderWithProviders(<FilmDetailPage />, { path: '/films/:id', route: '/films/12' });
}

const block = () => screen.getByRole('group', { name: 'Tu puntuación' }).parentElement!;
/** [Puntuar] / [Cambiar]. */
const trigger = () => within(screen.getByRole('group', { name: 'Tu puntuación' })).getByRole('button');
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

const others: OtherRating[] = [
  f.otherRating({ profile: { id: 3, name: 'Luis', avatar_id: 'avatar-07' }, stars: 2 }),
  f.otherRating({ profile: { id: 2, name: 'Ana', avatar_id: 'avatar-02' }, stars: 4.5 }),
  f.otherRating({ profile: { id: 4, name: 'Pablo', avatar_id: 'avatar-03' }, stars: 3 }),
  f.otherRating({ profile: { id: 5, name: 'Marta', avatar_id: 'avatar-04' }, stars: 5 }),
  f.otherRating({ profile: { id: 6, name: 'Juan', avatar_id: 'avatar-06' }, stars: 0.5 }),
];

describe('RatingsBlock', () => {
  it('not rated, nobody else: only "Tu puntuación" [Puntuar]; the TMDB meta line is unchanged', async () => {
    renderFilm();
    const mine = await screen.findByRole('group', { name: 'Tu puntuación' });
    expect(within(mine).getByText('Tu puntuación')).toBeInTheDocument();
    expect(within(mine).getByRole('button', { name: 'Puntuar' })).toBeInTheDocument();
    expect(within(mine).queryByRole('img')).not.toBeInTheDocument();
    expect(screen.queryByText('Media de la familia')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Puntuaciones de la familia' })).not.toBeInTheDocument();
    expect(screen.getByText('2021 · 2 h 35 min · Ciencia ficción, Aventura · 7,8')).toBeInTheDocument();
  });

  it('rated: stars + [Cambiar]; the household average "4,0 (3)" with its label; others newest first, 3 then "y 2 más" / "Ver menos"', async () => {
    serveFilm({ my_rating: 4, ratings: others, household_average: { stars: 4, count: 3 } });
    const { user } = renderFilm();
    const mine = await screen.findByRole('group', { name: 'Tu puntuación' });
    expect(within(mine).getByRole('img', { name: '4 estrellas' })).toBeInTheDocument();
    expect(within(mine).getByRole('button', { name: 'Cambiar' })).toBeInTheDocument();
    const average = screen.getByRole('group', { name: 'Media de la familia' });
    expect(within(average).getByRole('img', { name: 'Media de la familia: 4,0 estrellas, 3 puntuaciones' })).toBeInTheDocument();
    expect(within(average).getByText('4,0 (3)')).toBeInTheDocument();

    const list = screen.getByRole('list', { name: 'Puntuaciones de la familia' });
    const rows = () => within(list).getAllByRole('listitem').map((li) => li.textContent);
    expect(rows()).toEqual(['LLuis', 'AAna', 'PPablo']);
    expect(within(list).getAllByRole('img').map((img) => img.getAttribute('aria-label'))).toEqual([
      '2 estrellas', '4,5 estrellas', '3 estrellas',
    ]);
    const toggle = screen.getByRole('button', { name: 'y 2 más' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(rows()).toEqual(['LLuis', 'AAna', 'PPablo', 'MMarta', 'JJuan']);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveTextContent('Ver menos');
    expect(toggle).toHaveFocus();
    await user.click(toggle);
    expect(rows()).toHaveLength(3);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('the average icons are rounded to ½, the text keeps one decimal', async () => {
    serveFilm({ my_rating: null, ratings: others.slice(0, 2), household_average: { stars: 3.3, count: 2 } });
    renderFilm();
    const average = await screen.findByRole('group', { name: 'Media de la familia' });
    expect(within(average).getByText('3,3 (2)')).toBeInTheDocument();
    const fills = [...within(average).getByRole('img').querySelectorAll('svg')].map((s) => s.getAttribute('data-fill'));
    expect(fills).toEqual(['full', 'full', 'full', 'half', 'empty']);
  });

  it('three others or fewer: no toggle', async () => {
    serveFilm({ ratings: others.slice(0, 3) });
    renderFilm();
    await screen.findByRole('list', { name: 'Puntuaciones de la familia' });
    expect(screen.queryByRole('button', { name: /más/ })).not.toBeInTheDocument();
  });

  it('[Puntuar] opens the manual dialog; a save patches the block at once (optimistic) and rolls back on a 500', async () => {
    const gate = deferred();
    server.use(
      http.put(`${API}/ratings/films/:id`, async () => {
        await gate.promise;
        return apiError(500, 'internal_error');
      }),
    );
    const { user } = renderFilm();
    await user.click(await screen.findByRole('button', { name: 'Puntuar' }));
    const dialog = screen.getByRole('dialog', { name: 'Dune' });
    expect(within(dialog).queryByText('¿Qué te ha parecido?')).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole('button').map((b) => b.textContent)).toEqual(['Guardar', 'Cancelar']);
    fireEvent.keyDown(within(dialog).getByRole('slider'), { key: 'End' });
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(within(screen.getByRole('group', { name: 'Tu puntuación' })).getByRole('img', { name: '5 estrellas' })).toBeInTheDocument());
    // While the write is pending the block's button is disabled (aria-disabled: it stays focusable).
    expect(within(screen.getByRole('group', { name: 'Tu puntuación' })).getByRole('button', { name: 'Cambiar' })).toHaveAttribute('aria-disabled', 'true');
    gate.resolve();
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('No se ha podido guardar la puntuación.');
    await waitFor(() =>
      expect(within(screen.getByRole('group', { name: 'Tu puntuación' })).getByRole('button', { name: 'Puntuar' })).not.toHaveAttribute('aria-disabled'),
    );
  });

  describe('the settle refetch never delays the form (review W1)', () => {
    /** The first detail GET answers; later ones (the settle invalidation) wait for the returned gate. */
    function holdRefetch() {
      const gate = deferred();
      let gets = 0;
      server.use(
        http.get(`${API}/films/:id`, async () => {
          gets += 1;
          if (gets > 1) await gate.promise;
          return HttpResponse.json(f.filmDetail({}));
        }),
      );
      return gate;
    }

    it('a successful save closes the dialog while the detail refetch is still pending', async () => {
      const gate = holdRefetch();
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Puntuar' }));
      fireEvent.keyDown(within(screen.getByRole('dialog', { name: 'Dune' })).getByRole('slider'), { key: 'End' });
      await user.click(screen.getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(rec.find('GET', '/api/films/12')).toHaveLength(2));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      gate.resolve();
      await rec.settled();
    });

    it('a failed save shows [Reintentar] while the detail refetch is still pending', async () => {
      const gate = holdRefetch();
      server.use(http.put(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error')));
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Puntuar' }));
      const dialog = screen.getByRole('dialog', { name: 'Dune' });
      fireEvent.keyDown(within(dialog).getByRole('slider'), { key: 'End' });
      await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(rec.find('GET', '/api/films/12')).toHaveLength(2));
      expect(await within(dialog).findByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
      gate.resolve();
      await rec.settled();
    });
  });

  it('[Cambiar] → [Quitar puntuación]: optimistic "Puntuar" and DELETE; a failure restores the stars with the update error toast', async () => {
    serveFilm({ my_rating: 3 });
    server.use(http.delete(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error')));
    const rec = recordRequests();
    const { user } = renderFilm();
    await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
    const dialog = screen.getByRole('dialog', { name: 'Dune' });
    expect(within(dialog).getByRole('slider')).toHaveAttribute('aria-valuetext', '3 estrellas');
    await user.click(within(dialog).getByRole('button', { name: 'Quitar puntuación' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(toastRegion()).toHaveTextContent('No se ha podido actualizar tu puntuación.'));
    expect(toastRegion()).not.toHaveTextContent('Puntuación guardada');
    await waitFor(() => expect(within(block()).getByRole('img', { name: '3 estrellas' })).toBeInTheDocument());
    expect(rec.find('DELETE', '/api/ratings/films/12')).toHaveLength(1);
  });

  describe('focus (review W2)', () => {
    it('[Quitar puntuación] returns focus to the block button, which stays focused while the DELETE is pending and ignores clicks', async () => {
      serveFilm({ my_rating: 3 });
      const gate = deferred();
      server.use(
        http.delete(`${API}/ratings/films/:id`, async () => {
          await gate.promise;
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
      await user.click(screen.getByRole('button', { name: 'Quitar puntuación' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(trigger()).toHaveTextContent('Puntuar');
      expect(trigger()).toHaveFocus();
      expect(trigger()).toHaveAttribute('aria-disabled', 'true');
      expect(trigger()).toBeEnabled();
      await user.click(trigger());
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      gate.resolve();
      await waitFor(() => expect(trigger()).not.toHaveAttribute('aria-disabled'));
      expect(trigger()).toHaveFocus();
      await rec.settled();
    });

    it('a successful save returns focus to the block button', async () => {
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Puntuar' }));
      fireEvent.keyDown(within(screen.getByRole('dialog', { name: 'Dune' })).getByRole('slider'), { key: 'End' });
      await user.click(screen.getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(trigger()).toHaveFocus();
    });
  });

  it('a successful removal shows "Puntuar" and no toast', async () => {
    let rated = true;
    server.use(
      http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ my_rating: rated ? 3 : null }))),
      http.delete(`${API}/ratings/films/:id`, () => {
        rated = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = renderFilm();
    await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
    await user.click(screen.getByRole('button', { name: 'Quitar puntuación' }));
    expect(await screen.findByRole('button', { name: 'Puntuar' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Puntuar' })).not.toHaveAttribute('aria-disabled'));
    expect(toastRegion()).toHaveTextContent('');
  });

  describe('server unreachable: the settle refetch fails too, the cached detail stays (validation Run 1, prd2 step 7)', () => {
    /** The first detail GET answers; after `stop()` every request is a network error (server stopped). */
    function serveThenStop(path: 'films' | 'shows', detail: () => object) {
      let stopped = false;
      server.use(
        http.get(`${API}/${path}/:id`, () => (stopped ? HttpResponse.error() : HttpResponse.json(detail()))),
        http.put(`${API}/ratings/${path}/:id`, () => HttpResponse.error()),
        http.delete(`${API}/ratings/${path}/:id`, () => HttpResponse.error()),
      );
      return () => (stopped = true);
    }

    it('film: Guardar keeps the dialog open with the error line and [Reintentar]; the page is not replaced', async () => {
      const stop = serveThenStop('films', () => f.filmDetail({ my_rating: 3 }));
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
      stop();
      const dialog = screen.getByRole('dialog', { name: 'Dune' });
      fireEvent.keyDown(within(dialog).getByRole('slider'), { key: 'End' });
      await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('No se ha podido guardar la puntuación.');
      // The settle invalidation refetched the detail and that failed as well.
      await waitFor(() => expect(rec.find('GET', '/api/films/12').length).toBeGreaterThanOrEqual(2));
      await waitFor(() => expect(within(block()).getByRole('img', { name: '3 estrellas' })).toBeInTheDocument());
      expect(screen.getByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: 'Dune' })).toBeInTheDocument();
      expect(screen.queryByText('No se puede conectar con el servidor.')).not.toBeInTheDocument();
    });

    it('film: Quitar puntuación rolls the block back with the toast; the page is not replaced', async () => {
      const stop = serveThenStop('films', () => f.filmDetail({ my_rating: 3 }));
      const rec = recordRequests();
      const { user } = renderFilm();
      await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
      stop();
      await user.click(screen.getByRole('button', { name: 'Quitar puntuación' }));
      await waitFor(() => expect(toastRegion()).toHaveTextContent('No se ha podido actualizar tu puntuación.'));
      await waitFor(() => expect(rec.find('GET', '/api/films/12').length).toBeGreaterThanOrEqual(2));
      await waitFor(() => expect(within(block()).getByRole('img', { name: '3 estrellas' })).toBeInTheDocument());
      expect(screen.getByRole('heading', { level: 1, name: 'Dune' })).toBeInTheDocument();
      expect(screen.queryByText('No se puede conectar con el servidor.')).not.toBeInTheDocument();
    });

    it('show: Guardar keeps the dialog open with [Reintentar]; the page is not replaced', async () => {
      const stop = serveThenStop('shows', () => f.showDetail({ my_rating: 3 }));
      const rec = recordRequests();
      const { user } = renderWithProviders(<ShowDetailPage />, { path: '/shows/:id', route: '/shows/7' });
      await user.click(await screen.findByRole('button', { name: 'Cambiar' }));
      stop();
      const dialog = screen.getByRole('dialog');
      fireEvent.keyDown(within(dialog).getByRole('slider'), { key: 'End' });
      await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('No se ha podido guardar la puntuación.');
      await waitFor(() => expect(rec.find('GET', '/api/shows/7').length).toBeGreaterThanOrEqual(2));
      await waitFor(() => expect(within(block()).getByRole('img', { name: '3 estrellas' })).toBeInTheDocument());
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
      expect(screen.queryByText('No se puede conectar con el servidor.')).not.toBeInTheDocument();
    });
  });

  it('show detail: the block sits with "N sin ver"', async () => {
    server.use(
      http.get(`${API}/shows/:id`, () =>
        HttpResponse.json(f.showDetail({ my_rating: 5, ratings: others.slice(0, 2), household_average: { stars: 3.5, count: 3 } })),
      ),
    );
    renderWithProviders(<ShowDetailPage />, { path: '/shows/:id', route: '/shows/7' });
    const mine = await screen.findByRole('group', { name: 'Tu puntuación' });
    expect(within(mine).getByRole('img', { name: '5 estrellas' })).toBeInTheDocument();
    expect(screen.getByText('150 sin ver')).toBeInTheDocument();
    expect(screen.getByText('3,5 (3)')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Puntuaciones de la familia' })).getAllByRole('listitem')).toHaveLength(2);
  });
});
