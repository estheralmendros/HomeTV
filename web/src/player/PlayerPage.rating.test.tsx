// Player end state and the rating prompt: nothing during
// playback; at `ended` without a next item the inline form replaces "Fin de la reproducción";
// answers are final; leaving with the form visible = Ahora no (unless a save is pending);
// leaving without it offers the prompt over the screen returned to.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EpisodeDetail, RatingPrompt } from '@/api/types';
import * as f from '../../tests/fixtures';
import { advance, tick, until } from '../../tests/flush';
import { stubMediaSession, type FakeMediaSession } from '../../tests/mediaSession';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import {
  answerPlayback,
  deferred,
  renderPlayer,
  serveEpisodes,
  useFakePlayerTimers,
  type PlayerHarness,
} from '../../tests/player';
import { currentUrl } from '../../tests/renderWithProviders';
import { hasPendingFinal, type ItemRef } from './progressSync';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const DURATION = 1000;

/** A few real macrotasks (requests and renders settle; these tests run on real timers). */
async function settle() {
  for (let i = 0; i < 10; i += 1) await tick();
}

/**
 * The positive signal an absence assertion after leaving needs: the final (keepalive) report was
 * sent and fully handled (`registerFinalReport` settled, so a prompt it carried has already been
 * offered to the host); then the renders flush.
 */
async function finalReportHandled(p: PlayerHarness, item: ItemRef = { type: 'film', id: 12 }) {
  await until(() => expect(p.puts().filter((c) => c.keepalive)).toHaveLength(1));
  await p.rec.settled();
  await until(() => expect(hasPendingFinal(item)).toBe(false));
  await settle();
}
const dune = f.ratingPrompt();
const office: RatingPrompt = f.ratingPrompt({ kind: 'show', id: 7, title: 'The Office', poster: null });

type Which = 'all' | 'final' | 'non-final' | 'none';

/** PUT /api/progress answers `rating_prompt` on the chosen reports (final = keepalive). */
function serveProgress(prompt: RatingPrompt, which: Which, { finalFails = false } = {}) {
  server.use(
    http.put(`${API}/progress`, async ({ request }) => {
      const body = (await request.json()) as { media_file_id: number; position_seconds: number };
      const final = request.keepalive;
      if (final && finalFails) return apiError(500, 'internal_error');
      const carries = which === 'all' || (which === 'final' && final) || (which === 'non-final' && !final);
      return HttpResponse.json(
        f.progressReport({ media_file_id: body.media_file_id, position_seconds: body.position_seconds, duration_seconds: DURATION, rating_prompt: carries ? prompt : null }),
      );
    }),
  );
}

function serveFilm() {
  const file = f.mediaFile({ duration_seconds: DURATION });
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(f.filmDetail({ media_files: [file], progress: null }))));
  answerPlayback(f.playbackDirect({ duration_seconds: DURATION }));
}

function playToEnd(p: PlayerHarness) {
  p.at(DURATION - 25);
  p.at(DURATION, 'pause');
  p.at(DURATION, 'ended');
}

const form = () => screen.queryByRole('dialog', { name: '¿Qué te ha parecido?' });
const endDialog = () => screen.queryByRole('dialog', { name: 'Fin de la reproducción' });
const hostDialog = (name = 'Dune') => screen.queryByRole('dialog', { name });
const dismissals = (p: PlayerHarness, path = '/api/ratings/films/12/dismiss-prompt') => p.rec.find('POST', path);
const appToasts = () => screen.getByRole('status', { name: 'Notificaciones' });
const playerRoot = () => screen.queryByTestId('player-root');

async function filmAtEnd(which: Which = 'all') {
  serveFilm();
  serveProgress(dune, which);
  const p = renderPlayer('/watch/film/12');
  await p.startDirect(0);
  playToEnd(p);
  return p;
}

describe('film: the inline rating form at the end', () => {
  it('a report carrying rating_prompt shows nothing during playback', async () => {
    serveFilm();
    serveProgress(dune, 'all');
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.pauseAt(950);
    await until(() => expect(p.puts()).toHaveLength(1));
    await p.rec.settled();
    await settle();
    expect(form()).not.toBeInTheDocument();
    expect(hostDialog()).not.toBeInTheDocument();
    // Positive signal: the player did receive that prompt (leaving offers it over "/").
    void p.router.navigate(-1);
    await until(() => expect(hostDialog()).toBeInTheDocument());
  });

  it('ended without a next item: the form replaces "Fin de la reproducción"; [Ahora no] then shows the end dialog (one dismissal)', async () => {
    const p = await filmAtEnd();
    const inline = await screen.findByRole('dialog', { name: '¿Qué te ha parecido?' });
    expect(endDialog()).not.toBeInTheDocument();
    expect(within(inline).getByText('Dune')).toBeInTheDocument();
    expect(within(inline).getAllByRole('button').map((b) => b.textContent)).toEqual(['Guardar', 'Ahora no']);
    fireEvent.click(within(inline).getByRole('button', { name: 'Ahora no' }));
    expect(await screen.findByRole('dialog', { name: 'Fin de la reproducción' })).toBeInTheDocument();
    expect(form()).not.toBeInTheDocument();
    await until(() => expect(dismissals(p)).toHaveLength(1));
  });

  it('Ahora no, then Volver: nothing opens over the screen returned to, even with the final report carrying the prompt', async () => {
    const p = await filmAtEnd('all');
    fireEvent.click(await screen.findByRole('button', { name: 'Ahora no' }));
    fireEvent.click(within((await screen.findByRole('dialog', { name: 'Fin de la reproducción' })) as HTMLElement).getByRole('button', { name: 'Volver' }));
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await finalReportHandled(p);
    expect(hostDialog()).not.toBeInTheDocument();
    await p.rec.settled();
    expect(dismissals(p)).toHaveLength(1);
  });

  it('Guardar shows "Puntuación guardada" as a player toast and the end dialog; leaving sends no dismissal', async () => {
    const p = await filmAtEnd();
    const slider = await screen.findByRole('slider', { name: 'Puntuación' });
    fireEvent.keyDown(slider, { key: 'End' });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await until(() => expect(within(playerRoot()!).getByText('Puntuación guardada')).toBeInTheDocument());
    expect(appToasts()).not.toHaveTextContent('Puntuación guardada');
    expect(await screen.findByRole('dialog', { name: 'Fin de la reproducción' })).toBeInTheDocument();
    await p.rec.settled();
    expect(p.rec.find('PUT', '/api/ratings/films/12').map((c) => c.body)).toEqual([{ stars: 5 }]);
    void p.router.navigate(-1);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await finalReportHandled(p);
    await p.rec.settled();
    expect(dismissals(p)).toHaveLength(0);
    expect(hostDialog()).not.toBeInTheDocument();
  });

  it('a failed save keeps the inline form with the error and [Reintentar]; Reintentar then shows the end dialog', async () => {
    server.use(http.put(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error'), { once: true }));
    await filmAtEnd();
    fireEvent.keyDown(await screen.findByRole('slider', { name: 'Puntuación' }), { key: 'End' });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No se ha podido guardar la puntuación.');
    expect(form()).toBeInTheDocument();
    expect(endDialog()).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('dialog', { name: 'Fin de la reproducción' })).toBeInTheDocument();
    expect(form()).not.toBeInTheDocument();
  });

  it('leaving after a failed save sends one dismissal and opens nothing over the screen returned to', async () => {
    server.use(http.put(`${API}/ratings/films/:id`, () => apiError(500, 'internal_error')));
    const p = await filmAtEnd();
    fireEvent.keyDown(await screen.findByRole('slider', { name: 'Puntuación' }), { key: 'End' });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await screen.findByRole('button', { name: 'Reintentar' });
    void p.router.navigate(-1);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await until(() => expect(dismissals(p)).toHaveLength(1));
    await finalReportHandled(p);
    expect(hostDialog()).not.toBeInTheDocument();
    await p.rec.settled();
    expect(dismissals(p)).toHaveLength(1);
  });

  it.each([
    ['succeeds', () => new HttpResponse(null, { status: 204 }), 'Puntuación guardada'],
    ['fails', () => apiError(500, 'internal_error'), 'No se ha podido guardar la puntuación.'],
  ])('leaving while the inline save is pending sends no dismissal; when it %s an app toast says so', async (_n, answer, text) => {
    const gate = deferred();
    server.use(
      http.put(`${API}/ratings/films/:id`, async () => {
        await gate.promise;
        return answer();
      }),
    );
    const p = await filmAtEnd();
    fireEvent.keyDown(await screen.findByRole('slider', { name: 'Puntuación' }), { key: 'End' });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await until(() => expect(p.rec.find('PUT', '/api/ratings/films/12')).toHaveLength(1));
    void p.router.navigate(-1);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await until(() => expect(playerRoot()).not.toBeInTheDocument());
    gate.resolve();
    await until(() => expect(appToasts()).toHaveTextContent(text));
    await finalReportHandled(p);
    expect(dismissals(p)).toHaveLength(0);
    expect(hostDialog()).not.toBeInTheDocument();
  });

  it.each([
    ['Volver in the top bar', () => fireEvent.click(within(playerRoot()!).getAllByRole('button', { name: 'Volver' })[0]!)],
    ['browser Back', (p: PlayerHarness) => void p.router.navigate(-1)],
  ])('leaving with the form unanswered (%s) sends one dismissal and opens nothing', async (_n, leave) => {
    const p = await filmAtEnd();
    await screen.findByRole('dialog', { name: '¿Qué te ha parecido?' });
    leave(p);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await until(() => expect(dismissals(p)).toHaveLength(1));
    await finalReportHandled(p);
    expect(hostDialog()).not.toBeInTheDocument();
    await p.rec.settled();
    expect(dismissals(p)).toHaveLength(1);
  });

  it('Escape in the inline form answers Ahora no', async () => {
    const p = await filmAtEnd();
    await until(() => expect(screen.getByRole('slider', { name: 'Puntuación' })).toHaveFocus());
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Puntuación' }), { key: 'Escape' });
    expect(await screen.findByRole('dialog', { name: 'Fin de la reproducción' })).toBeInTheDocument();
    await until(() => expect(dismissals(p)).toHaveLength(1));
  });
});

describe('player keys while the form shows', () => {
  let ms: FakeMediaSession;
  beforeEach(() => {
    ms = stubMediaSession();
  });
  afterEach(() => ms.restore());

  it('Space, arrows, k and a media-session play keep `ended`; → on the stars only changes them; shortcuts return with the end dialog', async () => {
    const p = await filmAtEnd();
    const slider = await screen.findByRole('slider', { name: 'Puntuación' });
    const play = vi.spyOn(p.video(), 'play');
    for (const key of [' ', 'ArrowRight', 'ArrowLeft', 'k', 'j', 'l']) fireEvent.keyDown(window, { key });
    ms.trigger('play');
    fireEvent.keyDown(slider, { key: 'ArrowRight' });
    fireEvent.keyDown(slider, { key: ' ' });
    expect(play).not.toHaveBeenCalled();
    expect(p.video().currentTime).toBe(DURATION);
    expect(slider).toHaveAttribute('aria-valuetext', '0,5 estrellas');
    expect(form()).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Ahora no' }));
    await screen.findByRole('dialog', { name: 'Fin de la reproducción' });
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    expect(p.video().currentTime).toBe(DURATION - 10);
  });
});

describe('last episode of a show', () => {
  const file = f.episodeFile({ duration_seconds: DURATION });
  const last: EpisodeDetail = f.episodeDetail({ id: 347, episode_number: 5, media_files: [file], next_episode_id: null, progress: null });

  it('the form replaces the end-of-series state, with no countdown', async () => {
    useFakePlayerTimers();
    serveEpisodes([last]);
    answerPlayback(f.playbackDirect({ url: `/api/media-files/${file.id}/stream`, duration_seconds: DURATION }));
    serveProgress(office, 'all');
    const p = renderPlayer('/watch/episode/347');
    await p.startDirect(0);
    playToEnd(p);
    await until(() => expect(form()).toBeInTheDocument());
    expect(within(form()!).getByText('The Office', { selector: 'p' })).toBeInTheDocument();
    expect(endDialog()).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Siguiente episodio' })).not.toBeInTheDocument();
    await advance(15_000);
    expect(screen.queryByText(/Se reproducirá en/)).not.toBeInTheDocument();
    expect(currentUrl(p.router)).toBe('/watch/episode/347');
  });
});

describe('leaving without the form visible offers the prompt over the screen returned to', () => {
  it('before the end, after a report carried it: the host dialog opens once over "/"', async () => {
    serveFilm();
    serveProgress(dune, 'non-final');
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.pauseAt(950);
    await until(() => expect(p.puts()).toHaveLength(1));
    await settle();
    void p.router.navigate(-1);
    expect(await screen.findByRole('dialog', { name: 'Dune' })).toHaveTextContent('¿Qué te ha parecido?');
    expect(currentUrl(p.router)).toBe('/');
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await p.rec.settled();
    expect(dismissals(p)).toHaveLength(0);
  });

  it('only the final report carried it: the host dialog opens over "/"', async () => {
    serveFilm();
    serveProgress(dune, 'final');
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.pauseAt(950);
    await until(() => expect(p.puts()).toHaveLength(1));
    void p.router.navigate(-1);
    expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
  });

  it('an earlier report carried it and the final report fails: the host dialog still opens', async () => {
    serveFilm();
    server.use(
      http.put(`${API}/progress`, ({ request }) =>
        request.keepalive ? apiError(500, 'internal_error') : HttpResponse.json(f.progressReport({ rating_prompt: dune })),
      ),
    );
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.pauseAt(950);
    await until(() => expect(p.puts()).toHaveLength(1));
    await settle();
    void p.router.navigate(-1);
    expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
  });

  it('ended at the last episode with next-up still loading, final PUT failing: the host dialog opens over the detail', async () => {
    const file = f.episodeFile({ duration_seconds: DURATION });
    const ep = f.episodeDetail({ id: 346, media_files: [file], next_episode_id: 347, progress: null });
    const hold = deferred();
    serveEpisodes([ep, f.episodeDetail({ id: 347, next_episode_id: null })], { 347: hold.promise });
    answerPlayback(f.playbackDirect({ url: `/api/media-files/${file.id}/stream`, duration_seconds: DURATION }));
    server.use(
      http.put(`${API}/progress`, ({ request }) =>
        request.keepalive ? apiError(500, 'internal_error') : HttpResponse.json(f.progressReport({ rating_prompt: office })),
      ),
    );
    const p = renderPlayer('/watch/episode/346', { history: ['/shows/7'] });
    await p.startDirect(0);
    playToEnd(p);
    await until(() => expect(p.puts().length).toBeGreaterThanOrEqual(2));
    await p.rec.settled();
    await settle();
    expect(form()).not.toBeInTheDocument();
    void p.router.navigate(-1);
    expect(await screen.findByRole('dialog', { name: 'The Office' })).toBeInTheDocument();
    expect(currentUrl(p.router)).toBe('/shows/7');
    hold.resolve();
  });

  it('never after the title was answered in the player', async () => {
    const p = await filmAtEnd('all');
    fireEvent.keyDown(await screen.findByRole('slider', { name: 'Puntuación' }), { key: 'End' });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await screen.findByRole('dialog', { name: 'Fin de la reproducción' });
    fireEvent.click(screen.getByRole('button', { name: 'Volver a reproducir' }));
    await until(() => expect(endDialog()).not.toBeInTheDocument());
    void p.router.navigate(-1);
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await finalReportHandled(p);
    expect(hostDialog()).not.toBeInTheDocument();
  });
});
