// RatingForm / RatingDialog: button sets, Escape semantics,
// pending guard, markHandled at the first save and at Ahora no, save success / error / retry.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { RatingKind, RatingPrompt } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { deferred } from '../../../tests/player';
import { renderWithProviders } from '../../../tests/renderWithProviders';
import { RatingForm, RatingDialog, type RatingFormProps } from './RatingDialog';
import { RatingPromptContext, type RatingPromptApi } from './ratingPrompt';

const target: RatingPrompt = f.ratingPrompt();
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });
const slider = () => screen.getByRole('slider', { name: 'Puntuación' });

function spyApi(): RatingPromptApi & { markHandled: ReturnType<typeof vi.fn<(kind: RatingKind, id: number) => void>> } {
  return { offer: vi.fn(), markHandled: vi.fn(), suppress: vi.fn(() => () => undefined) };
}

function renderDialog(props: Partial<RatingFormProps> = {}, api = spyApi()) {
  const onClose = vi.fn();
  const onAnswered = vi.fn();
  const rec = recordRequests();
  const view = renderWithProviders(
    <RatingPromptContext.Provider value={api}>
      <RatingDialog mode="prompt" target={target} initial={null} onClose={onClose} onAnswered={onAnswered} {...props} />
    </RatingPromptContext.Provider>,
  );
  return { ...view, onClose, onAnswered, rec, api };
}

function holdPut(answer: () => Response) {
  const gate = deferred();
  server.use(
    http.put(`${API}/ratings/films/:id`, async () => {
      await gate.promise;
      return answer();
    }),
  );
  return gate;
}

const dismissals = (rec: ReturnType<typeof recordRequests>) => rec.find('POST', '/api/ratings/films/12/dismiss-prompt');

describe('RatingDialog, prompt mode', () => {
  it('shows the poster, the title, the question and [Guardar] (disabled until a value) [Ahora no]; the stars get focus', async () => {
    renderDialog();
    const dialog = screen.getByRole('dialog', { name: 'Dune' });
    expect(within(dialog).getByText('¿Qué te ha parecido?')).toBeInTheDocument();
    expect(within(dialog).getAllByRole('button').map((b) => b.textContent)).toEqual(['Guardar', 'Ahora no']);
    expect(within(dialog).getByRole('button', { name: 'Guardar' })).toBeDisabled();
    expect(dialog.querySelector('img')).toHaveAttribute('src', expect.stringContaining('dune-poster'));
    await waitFor(() => expect(slider()).toHaveFocus());
    fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    expect(within(dialog).getByRole('button', { name: 'Guardar' })).toBeEnabled();
  });

  it('Guardar → markHandled before the PUT {stars}, toast "Puntuación guardada", closed, answered, ratingChanged', async () => {
    const api = spyApi();
    let handledBeforePut = false;
    server.use(
      http.put(`${API}/ratings/films/:id`, () => {
        handledBeforePut = api.markHandled.mock.calls.length === 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const view = renderDialog({}, api);
    const invalidate = vi.spyOn(view.queryClient, 'invalidateQueries');
    fireEvent.keyDown(slider(), { key: 'End' });
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' });
    expect(api.markHandled).not.toHaveBeenCalled();
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(api.markHandled).toHaveBeenCalledWith('film', 12);
    await waitFor(() => expect(view.onClose).toHaveBeenCalledTimes(1));
    expect(handledBeforePut).toBe(true);
    expect(view.onAnswered).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(toastRegion()).toHaveTextContent('Puntuación guardada'));
    await view.rec.settled();
    expect(view.rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 4.5 }]);
    expect(dismissals(view.rec)).toHaveLength(0);
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['activity'] }));
  });

  it('US-2: → nine times and Enter on the slider → one PUT {stars: 4.5}', async () => {
    const view = renderDialog();
    for (let i = 0; i < 9; i += 1) fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    fireEvent.keyDown(slider(), { key: 'Enter' });
    fireEvent.keyDown(slider(), { key: 'Enter' });
    await waitFor(() => expect(view.onClose).toHaveBeenCalled());
    await view.rec.settled();
    expect(view.rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 4.5 }]);
  });

  it('Enter on the empty slider does nothing', async () => {
    const view = renderDialog();
    fireEvent.keyDown(slider(), { key: 'Enter' });
    await view.rec.settled();
    expect(view.rec.calls).toHaveLength(0);
    expect(view.onClose).not.toHaveBeenCalled();
  });

  it('a failed save keeps the dialog with the error line and [Reintentar] (no toast); Reintentar saves the same value', async () => {
    server.use(http.put(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error'), { once: true }));
    const view = renderDialog();
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No se ha podido guardar la puntuación.');
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Reintentar', 'Ahora no']);
    expect(view.onClose).not.toHaveBeenCalled();
    expect(view.onAnswered).not.toHaveBeenCalled();
    expect(toastRegion()).toHaveTextContent('');
    await view.user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await waitFor(() => expect(view.onClose).toHaveBeenCalled());
    await view.rec.settled();
    expect(view.rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 5 }, { stars: 5 }]);
  });

  it('Enter on the stars, then a failed save: focus moves to [Reintentar] (review W2)', async () => {
    server.use(http.put(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error')));
    renderDialog();
    await waitFor(() => expect(slider()).toHaveFocus());
    fireEvent.keyDown(slider(), { key: 'End' });
    fireEvent.keyDown(slider(), { key: 'Enter' });
    const retry = await screen.findByRole('button', { name: 'Reintentar' });
    await waitFor(() => expect(retry).toHaveFocus());
  });

  it('a 404 on save closes, answers and toasts the not-found text', async () => {
    server.use(http.put(`${API}/ratings/films/:id`, () => apiError(404, 'not_found')));
    const view = renderDialog();
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(view.onClose).toHaveBeenCalled());
    expect(view.onAnswered).toHaveBeenCalled();
    await waitFor(() => expect(toastRegion()).toHaveTextContent('Este contenido no está disponible.'));
  });

  it.each([
    ['[Ahora no]', (view: ReturnType<typeof renderDialog>) => view.user.click(screen.getByRole('button', { name: 'Ahora no' }))],
    ['Escape', async () => void fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })],
  ])('%s → markHandled, one dismiss-prompt, closed, answered', async (_name, answer) => {
    const view = renderDialog();
    await answer(view);
    expect(view.api.markHandled).toHaveBeenCalledWith('film', 12);
    expect(view.onClose).toHaveBeenCalledTimes(1);
    expect(view.onAnswered).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
  });

  it('a failed dismissal is only logged', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    server.use(http.post(`${API}/ratings/films/:id/dismiss-prompt`, () => apiError(500, 'internal_error')));
    const view = renderDialog();
    await view.user.click(screen.getByRole('button', { name: 'Ahora no' }));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(toastRegion()).toHaveTextContent('');
    warn.mockRestore();
  });

  it('while the PUT is pending: Escape and [Ahora no] do nothing and send no dismissal; success then closes', async () => {
    const gate = holdPut(() => new HttpResponse(null, { status: 204 }));
    const view = renderDialog();
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ahora no' })).toHaveAttribute('aria-disabled', 'true'));
    // aria-disabled, not disabled: the clicked [Guardar] keeps the focus (review W2).
    const guardar = screen.getByRole('button', { name: 'Guardar' });
    expect(guardar).toHaveAttribute('aria-disabled', 'true');
    expect(guardar).toBeEnabled();
    expect(guardar).toHaveFocus();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: 'Ahora no' }));
    fireEvent.keyDown(slider(), { key: 'Enter' });
    expect(view.onClose).not.toHaveBeenCalled();
    gate.resolve();
    await waitFor(() => expect(view.onClose).toHaveBeenCalledTimes(1));
    await view.rec.settled();
    expect(dismissals(view.rec)).toHaveLength(0);
    expect(view.rec.find('PUT', '/api/ratings/films/12')).toHaveLength(1);
  });

  it('while the PUT is pending then fails: [Reintentar] shows, still no dismissal', async () => {
    const gate = holdPut(() => apiError(500, 'internal_error'));
    const view = renderDialog();
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    fireEvent.keyDown(document.body, { key: 'Escape' });
    gate.resolve();
    expect(await screen.findByRole('button', { name: 'Reintentar' })).not.toHaveAttribute('aria-disabled');
    await view.rec.settled();
    expect(dismissals(view.rec)).toHaveLength(0);
    expect(view.onClose).not.toHaveBeenCalled();
  });
});

describe('RatingDialog, manual mode', () => {
  it('not rated: [Guardar] [Cancelar], no question; Cancelar and Escape store nothing', async () => {
    const view = renderDialog({ mode: 'manual' });
    expect(screen.queryByText('¿Qué te ha parecido?')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Guardar', 'Cancelar']);
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Cancelar' }));
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(view.onClose).toHaveBeenCalledTimes(2);
    expect(view.onAnswered).not.toHaveBeenCalled();
    expect(view.api.markHandled).not.toHaveBeenCalled();
    await view.rec.settled();
    expect(view.rec.calls).toHaveLength(0);
  });

  it('rated: [Quitar puntuación] … [Guardar] [Cancelar], starting at the current value', () => {
    renderDialog({ mode: 'manual', initial: 4 });
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Quitar puntuación', 'Guardar', 'Cancelar']);
    expect(slider()).toHaveAttribute('aria-valuetext', '4 estrellas');
  });

  it('Guardar in manual mode never marks handled', async () => {
    const view = renderDialog({ mode: 'manual', initial: 4 });
    fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(view.onClose).toHaveBeenCalled());
    expect(view.api.markHandled).not.toHaveBeenCalled();
    await view.rec.settled();
    expect(view.rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 4.5 }]);
  });

  it('Quitar puntuación → closed at once, DELETE, no toast', async () => {
    const view = renderDialog({ mode: 'manual', initial: 4 });
    await view.user.click(screen.getByRole('button', { name: 'Quitar puntuación' }));
    expect(view.onClose).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(view.rec.find('DELETE', '/api/ratings/films/12')).toHaveLength(1));
    await view.rec.settled();
    expect(toastRegion()).toHaveTextContent('');
  });
});

describe('RatingForm, inline (player end state)', () => {
  function renderInline(props: Partial<RatingFormProps> = {}) {
    const onClose = vi.fn();
    const onAnswered = vi.fn();
    const notify = vi.fn();
    const rec = recordRequests();
    const view = renderWithProviders(
      <RatingForm mode="prompt" inline target={target} initial={null} onClose={onClose} onAnswered={onAnswered} notify={notify} {...props} />,
    );
    return { ...view, onClose, onAnswered, notify, rec };
  }

  it('the stars get focus; Escape (document listener) = Ahora no', async () => {
    const view = renderInline();
    await waitFor(() => expect(slider()).toHaveFocus());
    expect(fireEvent.keyDown(document.body, { key: 'Escape' })).toBe(false);
    expect(view.onAnswered).toHaveBeenCalledTimes(1);
    expect(view.onClose).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
  });

  it('Guardar reports through notify, not the app toast', async () => {
    const view = renderInline();
    fireEvent.keyDown(slider(), { key: 'End' });
    await view.user.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(view.notify).toHaveBeenCalledWith('Puntuación guardada'));
    expect(toastRegion()).toHaveTextContent('');
  });
});
