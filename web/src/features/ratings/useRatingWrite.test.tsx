// The "form gone" rule (ratings prd2 FR-W7): a save that settles after its form unmounted toasts
// through the app toast (both outcomes); while mounted, success goes to `notify` and an error
// shows no toast (the form's error line does).
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { FilmDetail } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { deferred } from '../../../tests/player';
import { renderWithProviders } from '../../../tests/renderWithProviders';
import { useRatingWrite } from './useRatingWrite';

function Probe({ notify }: { notify?: (text: string) => void }) {
  const write = useRatingWrite('film', 12, { notify });
  return (
    <>
      <button type="button" onClick={() => write.save(4.5)}>
        save
      </button>
      <button type="button" onClick={() => write.remove()}>
        remove
      </button>
      <span>{write.saving ? 'saving' : 'idle'}</span>
    </>
  );
}

function Host({ notify }: { notify?: (text: string) => void }) {
  const [shown, setShown] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setShown(false)}>
        unmount
      </button>
      {shown && <Probe notify={notify} />}
    </>
  );
}

const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

function holdPut(status: number | 'ok') {
  const gate = deferred();
  server.use(
    http.put(`${API}/ratings/films/:id`, async () => {
      await gate.promise;
      return status === 'ok' ? new HttpResponse(null, { status: 204 }) : apiError(status, 'internal_error');
    }),
  );
  return gate;
}

function seedFilm(queryClient: ReturnType<typeof renderWithProviders>['queryClient'], film: FilmDetail) {
  queryClient.setQueryData(queryKeys.film(12), film);
}

describe('useRatingWrite', () => {
  it('save: PUT {stars}, optimistic my_rating on the cached detail, success → notify (mounted), ratingChanged', async () => {
    const rec = recordRequests();
    const notify = vi.fn();
    const gate = holdPut('ok');
    const view = renderWithProviders(<Host notify={notify} />);
    seedFilm(view.queryClient, f.filmDetail({ my_rating: null }));
    const invalidate = vi.spyOn(view.queryClient, 'invalidateQueries');
    await view.user.click(screen.getByRole('button', { name: 'save' }));
    await waitFor(() => expect(view.queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.my_rating).toBe(4.5));
    expect(screen.getByText('saving')).toBeInTheDocument();
    gate.resolve();
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Puntuación guardada'));
    expect(toastRegion()).not.toHaveTextContent('Puntuación guardada');
    await rec.settled();
    expect(rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 4.5 }]);
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['activity'] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['film', 12] });
  });

  it('save error while mounted: snapshot restored, no toast (the form shows the error line)', async () => {
    const notify = vi.fn();
    const gate = holdPut(500);
    const view = renderWithProviders(<Host notify={notify} />);
    seedFilm(view.queryClient, f.filmDetail({ my_rating: 2 }));
    await view.user.click(screen.getByRole('button', { name: 'save' }));
    await waitFor(() => expect(view.queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.my_rating).toBe(4.5));
    gate.resolve();
    await waitFor(() => expect(screen.getByText('idle')).toBeInTheDocument());
    expect(view.queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.my_rating).toBe(2);
    expect(notify).not.toHaveBeenCalled();
    expect(toastRegion()).toHaveTextContent('');
  });

  it('form gone, PUT then succeeds → the app toast shows "Puntuación guardada", never notify', async () => {
    const notify = vi.fn();
    const gate = holdPut('ok');
    const view = renderWithProviders(<Host notify={notify} />);
    await view.user.click(screen.getByRole('button', { name: 'save' }));
    await view.user.click(screen.getByRole('button', { name: 'unmount' }));
    gate.resolve();
    await waitFor(() => expect(toastRegion()).toHaveTextContent('Puntuación guardada'));
    expect(notify).not.toHaveBeenCalled();
  });

  it('form gone, PUT then fails → the app toast shows the save error and the snapshot is restored', async () => {
    const notify = vi.fn();
    const gate = holdPut(500);
    const view = renderWithProviders(<Host notify={notify} />);
    seedFilm(view.queryClient, f.filmDetail({ my_rating: 3 }));
    await view.user.click(screen.getByRole('button', { name: 'save' }));
    await view.user.click(screen.getByRole('button', { name: 'unmount' }));
    gate.resolve();
    await waitFor(() => expect(toastRegion()).toHaveTextContent('No se ha podido guardar la puntuación.'));
    expect(view.queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.my_rating).toBe(3);
    expect(notify).not.toHaveBeenCalled();
  });

  it('form gone, PUT 404 → the app toast shows the not-found text', async () => {
    const gate = deferred();
    server.use(
      http.put(`${API}/ratings/films/:id`, async () => {
        await gate.promise;
        return apiError(404, 'not_found');
      }),
    );
    const view = renderWithProviders(<Host />);
    await view.user.click(screen.getByRole('button', { name: 'save' }));
    await view.user.click(screen.getByRole('button', { name: 'unmount' }));
    gate.resolve();
    await waitFor(() => expect(toastRegion()).toHaveTextContent('Este contenido no está disponible.'));
  });

  it('remove: optimistic null, success shows no toast; failure restores and shows the update error', async () => {
    const view = renderWithProviders(<Host />);
    seedFilm(view.queryClient, f.filmDetail({ my_rating: 4 }));
    await view.user.click(screen.getByRole('button', { name: 'remove' }));
    await waitFor(() => expect(screen.getByText('idle')).toBeInTheDocument());
    expect(toastRegion()).toHaveTextContent('');

    server.use(http.delete(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error')));
    seedFilm(view.queryClient, f.filmDetail({ my_rating: 4 }));
    await view.user.click(screen.getByRole('button', { name: 'remove' }));
    await waitFor(() => expect(toastRegion()).toHaveTextContent('No se ha podido actualizar tu puntuación.'));
    expect(toastRegion()).not.toHaveTextContent('Puntuación guardada');
    expect(view.queryClient.getQueryData<FilmDetail>(queryKeys.film(12))?.my_rating).toBe(4);
  });
});
