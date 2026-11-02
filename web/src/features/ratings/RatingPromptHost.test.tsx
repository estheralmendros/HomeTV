// RatingPromptHost: offer, dedup, markHandled, suppression,
// profile change, navigation = Ahora no (except a pending save), session end = no answer.
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { useEffect, useState } from 'react';
import { describe, expect, it } from 'vitest';
import { apiFetch } from '@/api/client';
import type { RatingPrompt } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { deferred } from '../../../tests/player';
import { currentUrl, renderWithProviders } from '../../../tests/renderWithProviders';
import { useRatingPrompt } from './ratingPrompt';

const dune = f.ratingPrompt();
const office: RatingPrompt = f.ratingPrompt({ kind: 'show', id: 7, title: 'The Office', poster: null });

/** Test controls for the host's API (rendered on every route). */
function Controls() {
  const { offer, markHandled, suppress } = useRatingPrompt();
  const { signIn, signOut } = useAuth();
  return (
    <>
      <button type="button" onClick={() => offer(dune)}>
        offer dune
      </button>
      <button type="button" onClick={() => offer(office)}>
        offer office
      </button>
      <button type="button" onClick={() => markHandled('film', 12)}>
        handled dune
      </button>
      <button type="button" onClick={() => signIn(f.sessionResponse(f.profile({ id: 2, name: 'Luis' })))}>
        switch profile
      </button>
      <button type="button" onClick={() => signOut()}>
        sign out
      </button>
      <button type="button" onClick={() => void apiFetch('/api/me').catch(() => undefined)}>
        expire
      </button>
      <Suppressor suppress={suppress} />
    </>
  );
}

/** Holds a suppression (like the player) while "hold" is on; a second hold checks the count. */
function Suppressor({ suppress }: { suppress: () => () => void }) {
  const [holds, setHolds] = useState(0);
  useEffect(() => {
    const releases = Array.from({ length: holds }, () => suppress());
    return () => releases.forEach((release) => release());
  }, [holds, suppress]);
  return (
    <>
      <button type="button" onClick={() => setHolds((n) => n + 1)}>
        hold
      </button>
      <button type="button" onClick={() => setHolds((n) => n - 1)}>
        release
      </button>
    </>
  );
}

function renderHost(options: { route?: string; history?: string[] } = {}) {
  const rec = recordRequests();
  const view = renderWithProviders(<Controls />, { route: options.route ?? '/films/12', history: options.history ?? ['/'] });
  const click = (name: string) => fireEvent.click(screen.getAllByRole('button', { name })[0]!);
  return { ...view, rec, click };
}

const promptDialog = (name = 'Dune') => screen.queryByRole('dialog', { name });
const dismissals = (rec: ReturnType<typeof recordRequests>, path = '/api/ratings/films/12/dismiss-prompt') => rec.find('POST', path);
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

describe('RatingPromptHost', () => {
  it('an offer opens the prompt dialog; a repeated offer opens it once', async () => {
    const view = renderHost();
    view.click('offer dune');
    view.click('offer dune');
    expect(await screen.findByRole('dialog', { name: 'Dune' })).toHaveTextContent('¿Qué te ha parecido?');
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
  });

  it('a title is offered at most once per host lifetime (answered or not)', async () => {
    const view = renderHost();
    view.click('offer dune');
    fireEvent.click(await screen.findByRole('button', { name: 'Ahora no' }));
    expect(promptDialog()).not.toBeInTheDocument();
    view.click('offer dune');
    await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
    expect(promptDialog()).not.toBeInTheDocument();
  });

  it('markHandled blocks later offers and drops a pending one', async () => {
    const view = renderHost();
    view.click('handled dune');
    view.click('offer dune');
    expect(promptDialog()).not.toBeInTheDocument();
    view.click('offer office');
    expect(await screen.findByRole('dialog', { name: 'The Office' })).toBeInTheDocument();
  });

  describe('suppressed (player mounted)', () => {
    it('nothing shows while suppressed; the newest unopened offer replaces the older and opens once released', async () => {
      const view = renderHost();
      view.click('hold');
      view.click('hold');
      view.click('offer dune');
      view.click('offer office');
      view.click('release');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      view.click('release');
      expect(await screen.findByRole('dialog', { name: 'The Office' })).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Ahora no' }));
      await waitFor(() => expect(dismissals(view.rec, '/api/ratings/shows/7/dismiss-prompt')).toHaveLength(1));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('markHandled drops a pending offer', async () => {
      const view = renderHost();
      view.click('hold');
      view.click('offer dune');
      view.click('handled dune');
      view.click('release');
      await view.rec.settled();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('a profile change drops the pending prompt and forgets handled titles', async () => {
    const view = renderHost();
    view.click('offer dune');
    fireEvent.click(await screen.findByRole('button', { name: 'Ahora no' }));
    view.click('switch profile');
    await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
    view.click('offer dune');
    expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
    view.click('switch profile');
    // Signing in again with the same profile id keeps it; another id clears it.
    expect(promptDialog()).toBeInTheDocument();
  });

  describe('navigation while the dialog is open', () => {
    it('= Ahora no: closes and sends one dismiss-prompt; no later offer reopens it', async () => {
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      void view.router.navigate(-1);
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
      expect(currentUrl(view.router)).toBe('/');
      view.click('offer dune');
      expect(promptDialog()).not.toBeInTheDocument();
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(1);
    });

    it('a same-page REPLACE (only the search changes, e.g. a season gone) is not a navigation: stays open, no dismissal', async () => {
      const view = renderHost({ route: '/shows/7?season=2' });
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      await act(() => view.router.navigate('/shows/7', { replace: true }));
      expect(currentUrl(view.router)).toBe('/shows/7');
      await view.rec.settled();
      expect(promptDialog()).toBeInTheDocument();
      expect(dismissals(view.rec)).toHaveLength(0);
      // A later real navigation still answers it.
      await act(() => view.router.navigate(-1));
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
    });

    it.each([
      ['a REPLACE to another page (e.g. a merged title)', '/films/13', { replace: true }],
      ['a PUSH to the same page with another search', '/films/12?file=3', undefined],
    ])('%s still counts: closes and sends one dismiss-prompt', async (_name, to, opts) => {
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      await act(() => view.router.navigate(to, opts));
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await waitFor(() => expect(dismissals(view.rec)).toHaveLength(1));
    });

    it('an offer made after a navigation opens over the new page and is not dismissed by it', async () => {
      const view = renderHost();
      await view.router.navigate('/shows/7');
      view.click('offer dune');
      expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(0);
    });

    it.each([
      ['succeeds', () => new HttpResponse(null, { status: 204 }), 'Puntuación guardada'],
      ['fails', () => apiError(500, 'internal_error'), 'No se ha podido guardar la puntuación.'],
    ])('with the PUT pending: closes, no dismissal; when it %s the app toast says so', async (_name, answer, text) => {
      const gate = deferred();
      server.use(
        http.put(`${API}/ratings/films/:id`, async () => {
          await gate.promise;
          return answer();
        }),
      );
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      fireEvent.keyDown(screen.getByRole('slider', { name: 'Puntuación' }), { key: 'End' });
      fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(view.rec.find('PUT', '/api/ratings/films/12')).toHaveLength(1));
      void view.router.navigate(-1);
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      gate.resolve();
      await waitFor(() => expect(toastRegion()).toHaveTextContent(text));
      expect(toastRegion().children).toHaveLength(1);
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(0);
      expect(promptDialog()).not.toBeInTheDocument();
    });
  });

  describe('session end is not an answer', () => {
    it('sign-out → /profiles: closes, no dismissal', async () => {
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      view.click('sign out');
      await waitFor(() => expect(currentUrl(view.router)).toBe('/profiles'));
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(0);
    });

    it('a 401 on another request → /profiles?reason=expired: closes, no dismissal', async () => {
      server.use(http.get(`${API}/me`, () => apiError(401, 'unauthorized')));
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      view.click('expire');
      await waitFor(() => expect(currentUrl(view.router)).toBe('/profiles?reason=expired'));
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(0);
    });

    it('"Cambiar de perfil" (→ /profiles, session kept) and /setup: closes, no dismissal', async () => {
      const view = renderHost();
      view.click('offer dune');
      await screen.findByRole('dialog', { name: 'Dune' });
      await view.router.navigate('/setup');
      await waitFor(() => expect(promptDialog()).not.toBeInTheDocument());
      await view.rec.settled();
      expect(dismissals(view.rec)).toHaveLength(0);
      // Dropped, not handled: the same tab offers it again later.
      view.click('offer dune');
      expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
    });
  });
});
