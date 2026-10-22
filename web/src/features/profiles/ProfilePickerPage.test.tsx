import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp } from '../../../tests/renderWithProviders';
import { queryKeys } from '@/api/queryKeys';
import { adminUnlock } from '@/auth/adminUnlock';
import { LAST_PROFILE_KEY, SESSION_KEY } from '@/auth/session';

const esther = f.adminProfile({ id: 1, name: 'Esther' });
const kids = f.profile({ id: 2, name: 'Kids', avatar_id: 'avatar-04' });

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
  server.use(http.get('/api/profiles', () => HttpResponse.json({ items: [esther, kids] })));
});
afterEach(() => rec.dispose());

describe('ProfilePickerPage', () => {
  it('lists profiles with the Administrador tag and no lock or PIN', async () => {
    renderApp({ route: '/profiles', session: null, profile: null });
    const admin = await screen.findByRole('button', { name: /Esther/ });
    expect(admin).toHaveTextContent('Administrador');
    expect(screen.getByRole('button', { name: /Kids/ })).not.toHaveTextContent('Administrador');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each([
    ['admin', 'Esther', 1],
    ['viewer', 'Kids', 2],
  ])('one click signs in a %s profile with a body-less session request', async (_kind, name, id) => {
    server.use(
      http.post('/api/profiles/:id/session', ({ params }) =>
        HttpResponse.json(f.sessionResponse(Number(params.id) === 1 ? esther : kids)),
      ),
    );
    const { user, router } = renderApp({ route: '/profiles', session: null, profile: null });
    await user.click(await screen.findByRole('button', { name: new RegExp(name) }));
    await screen.findByRole('heading', { name: 'Inicio' });
    expect(currentUrl(router)).toBe('/');
    await rec.settled();
    const [request] = rec.find('POST', `/api/profiles/${id}/session`);
    expect(request?.body).toBeNull();
    expect(request?.headers.get('content-type')).toBeNull();
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toMatchObject({ token: `token-${id}`, profile_id: id });
    expect(localStorage.getItem(LAST_PROFILE_KEY)).toBe(String(id));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('switching profile clears cached queries and the admin token', async () => {
    const { user, queryClient } = renderApp({
      route: '/profiles',
      profile: esther,
      adminUnlock: f.unlockResponse(),
    });
    queryClient.setQueryData(queryKeys.home(), f.homeResponse());
    queryClient.setQueryData(queryKeys.film(12), f.filmDetail());
    await user.click(await screen.findByRole('button', { name: /Kids/ }));
    await screen.findByRole('heading', { name: 'Inicio' });
    expect(queryClient.getQueryData(queryKeys.film(12))).toBeUndefined();
    expect(queryClient.getQueryData(queryKeys.me())).toMatchObject({ id: 2 });
    expect(adminUnlock.getAdminToken()).toBeNull();
  });

  it('404 profile_not_found refetches the list and shows a toast', async () => {
    server.use(http.post('/api/profiles/:id/session', () => apiError(404, 'profile_not_found')));
    const { user } = renderApp({ route: '/profiles', session: null, profile: null });
    await user.click(await screen.findByRole('button', { name: /Kids/ }));
    expect(await screen.findByText('El perfil ya no existe.')).toBeInTheDocument();
    await waitFor(() => expect(rec.find('GET', '/api/profiles')).toHaveLength(2));
  });

  it('shows the expired banner with ?reason=expired', async () => {
    renderApp({ route: '/profiles?reason=expired', session: null, profile: null });
    expect(await screen.findByText('Tu sesión ha caducado. Elige un perfil para continuar.')).toBeInTheDocument();
  });

  it('focuses the last chosen profile first', async () => {
    localStorage.setItem(LAST_PROFILE_KEY, '2');
    renderApp({ route: '/profiles', session: null, profile: null });
    await waitFor(() => expect(screen.getByRole('button', { name: /Kids/ })).toHaveFocus());
  });

  it('an empty list shows ErrorState with a link to /setup', async () => {
    server.use(http.get('/api/profiles', () => HttpResponse.json({ items: [] })));
    renderApp({ route: '/profiles', session: null, profile: null });
    expect(await screen.findByText('No hay ningún perfil configurado.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a la configuración inicial' })).toHaveAttribute('href', '/setup');
  });
});
