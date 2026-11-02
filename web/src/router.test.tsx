// Boot sequence, guards, the route table and document.title.
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import * as f from '../tests/fixtures';
import { apiError } from '../tests/msw/handlers';
import { server } from '../tests/msw/server';
import { currentUrl, renderApp } from '../tests/renderWithProviders';
import { getFilm } from '@/api/catalog';
import { queryKeys } from '@/api/queryKeys';
import { SESSION_KEY } from '@/auth/session';

describe('boot sequence', () => {
  it('needs_bootstrap routes every path to /setup', async () => {
    server.use(http.get('/api/info', () => HttpResponse.json(f.info({ needs_bootstrap: true }))));
    for (const route of ['/', '/films', '/profiles', '/admin/scan']) {
      const { router, unmount } = renderApp({ route, info: null, session: null, profile: null });
      await screen.findByRole('heading', { name: 'Bienvenido a HomeTV' });
      expect(currentUrl(router)).toBe('/setup');
      expect(document.title).toBe('Configuración inicial · HomeTV');
      unmount();
    }
  });

  it('/setup redirects to /profiles once bootstrapped', async () => {
    const { router } = renderApp({ route: '/setup', session: null, profile: null });
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(currentUrl(router)).toBe('/profiles');
  });

  it('without a stored session goes to the picker (no expired banner)', async () => {
    const { router } = renderApp({ route: '/films', session: null, profile: null });
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(currentUrl(router)).toBe('/profiles');
    expect(screen.queryByText('Tu sesión ha caducado. Elige un perfil para continuar.')).not.toBeInTheDocument();
  });

  it('an expired expires_at goes to /profiles?reason=expired with the banner', async () => {
    const { router } = renderApp({
      route: '/',
      session: f.session({ expires_at: '2020-01-01T00:00:00Z' }),
      profile: null,
    });
    await screen.findByText('Tu sesión ha caducado. Elige un perfil para continuar.');
    expect(currentUrl(router)).toBe('/profiles?reason=expired');
  });

  it('/api/me 401 → /profiles?reason=expired and the session is removed', async () => {
    server.use(http.get('/api/me', () => apiError(401, 'unauthorized')));
    const { router } = renderApp({ route: '/', profile: null });
    await screen.findByText('Tu sesión ha caducado. Elige un perfil para continuar.');
    expect(currentUrl(router)).toBe('/profiles?reason=expired');
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('/api/me 200 renders the requested route with its document.title', async () => {
    renderApp({ route: '/films', profile: null });
    await screen.findByRole('heading', { name: 'Películas' });
    await waitFor(() => expect(document.title).toBe('Películas · HomeTV'));
  });

  it('a 401 on any later query sends the tab to the picker and clears the cache', async () => {
    const { router, queryClient } = renderApp({ route: '/' });
    await screen.findByRole('heading', { name: 'Inicio' });
    // A key no mounted observer refetches (the Home page may refetch ['home'] right after clear()).
    queryClient.setQueryData(queryKeys.search('seeded'), { films: [], shows: [] });
    server.use(http.get('/api/films/:id', () => apiError(401, 'unauthorized')));
    await expect(queryClient.fetchQuery({ queryKey: queryKeys.film(12), queryFn: () => getFilm(12) })).rejects.toBeTruthy();
    await screen.findByText('Tu sesión ha caducado. Elige un perfil para continuar.');
    expect(currentUrl(router)).toBe('/profiles?reason=expired');
    expect(queryClient.getQueryData(queryKeys.search('seeded'))).toBeUndefined();
  });

  it('a network failure of /api/info shows ErrorState with [Reintentar]', async () => {
    let fail = true;
    server.use(
      http.get('/api/info', () => (fail ? HttpResponse.error() : HttpResponse.json(f.info()))),
    );
    const { user } = renderApp({ route: '/', info: null });
    await screen.findByText('No se puede conectar con el servidor.');
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('heading', { name: 'Inicio' });
  });

  it('a network failure of /api/me shows ErrorState with [Reintentar]', async () => {
    let fail = true;
    server.use(http.get('/api/me', () => (fail ? HttpResponse.error() : HttpResponse.json(f.profile()))));
    const { user } = renderApp({ route: '/', profile: null });
    await screen.findByText('No se puede conectar con el servidor.');
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('heading', { name: 'Inicio' });
  });

  it('after a pick the picker returns to the route the guard saved', async () => {
    const { user, router } = renderApp({ route: '/shows', session: null, profile: null });
    await user.click(await screen.findByRole('button', { name: /Kids/ }));
    await screen.findByRole('heading', { name: 'Series' });
    expect(currentUrl(router)).toBe('/shows');
  });
});

describe('route table', () => {
  it.each([
    ['/', 'Inicio', 'Inicio · HomeTV'],
    ['/films', 'Películas', 'Películas · HomeTV'],
    ['/shows', 'Series', 'Series · HomeTV'],
    ['/watchlist', 'Mi lista · 0', 'Mi lista · HomeTV'],
    ['/search?q=dune', 'Buscar', 'Buscar · HomeTV'],
    ['/films/12', 'Dune', 'Dune (2021) · HomeTV'],
    ['/shows/7?season=2', 'The Office', 'The Office (2005) · HomeTV'],
    ['/about', 'Acerca de', 'Acerca de · HomeTV'],
  ])('%s renders %s', async (route, heading, title) => {
    renderApp({ route });
    await screen.findByRole('heading', { level: 1, name: heading });
    await waitFor(() => expect(document.title).toBe(title));
  });

  it('/profiles sets its title', async () => {
    renderApp({ route: '/profiles', session: null, profile: null });
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(document.title).toBe('¿Quién está viendo? · HomeTV');
  });

  it('/watch/:type/:id is a lazy route without the AppShell (the player shell and its <video>)', async () => {
    renderApp({ route: '/watch/film/12' });
    await screen.findByRole('heading', { level: 1, name: 'Dune (2021)' });
    expect(screen.getByTestId('player-root').querySelector('video')).not.toBeNull();
    expect(screen.queryByRole('navigation', { name: 'Navegación principal' })).not.toBeInTheDocument();
  });

  it('/watch with an unknown type is not found', async () => {
    renderApp({ route: '/watch/song/1' });
    await screen.findByText('Página no encontrada');
  });

  it('/admin (admin, unlocked) redirects to /admin/scan in the lazy admin layout', async () => {
    const { router } = renderApp({ route: '/admin', profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await screen.findByRole('heading', { name: 'Administración' });
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/scan'));
    await waitFor(() => expect(document.title).toBe('Administración · HomeTV'));
  });

  it('unknown paths show NotFound with "Ir a Inicio"', async () => {
    const { user, router } = renderApp({ route: '/nope/really' });
    await screen.findByText('Página no encontrada');
    expect(document.title).toBe('Página no encontrada · HomeTV');
    await user.click(screen.getByRole('link', { name: 'Ir a Inicio' }));
    await screen.findByRole('heading', { name: 'Inicio' });
    expect(currentUrl(router)).toBe('/');
  });
});
