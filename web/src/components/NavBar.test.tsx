import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { currentUrl, renderApp, renderWithProviders } from '../../tests/renderWithProviders';
import { adminUnlock } from '@/auth/adminUnlock';
import { SESSION_KEY } from '@/auth/session';
import { AppShell } from './AppShell';

describe('NavBar', () => {
  it('has the logo link and Inicio / Películas / Series links', () => {
    renderWithProviders(<AppShell />);
    expect(screen.getByRole('link', { name: 'HomeTV, ir a Inicio' })).toHaveAttribute('href', '/');
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(within(nav).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['Inicio', '/'],
      ['Películas', '/films'],
      ['Series', '/shows'],
    ]);
  });

  it('has the mobile tab bar with Buscar', () => {
    renderWithProviders(<AppShell />);
    const tabs = screen.getByRole('navigation', { name: 'Secciones' });
    expect(within(tabs).getAllByRole('link').map((a) => a.textContent)).toEqual(['Inicio', 'Películas', 'Series', 'Buscar']);
    expect(within(tabs).getByRole('link', { name: 'Buscar' })).toHaveAttribute('href', '/search');
  });

  it('NavSearch submits to /search?q=…', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await screen.findByRole('searchbox', { name: 'Buscar títulos' });
    expect(input).toHaveAttribute('placeholder', 'Buscar títulos…');
    await user.type(input, '  la casa{Enter}');
    await screen.findByRole('heading', { level: 1, name: 'Buscar' });
    expect(currentUrl(router)).toBe('/search?q=la%20casa');
  });

  it('the avatar menu offers switch profile, about and sign out to viewers', async () => {
    const { user, router } = renderApp({ route: '/' });
    await user.click(await screen.findByRole('button', { name: 'Menú de perfil de Ana' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual([
      'Cambiar de perfil',
      'Acerca de',
      'Cerrar sesión',
    ]);
    await user.click(screen.getByRole('menuitem', { name: 'Acerca de' }));
    await screen.findByRole('heading', { level: 1, name: 'Acerca de' });
    expect(currentUrl(router)).toBe('/about');
  });

  it('"Cambiar de perfil" goes to the picker and keeps the stored session', async () => {
    const { user, router } = renderApp({ route: '/' });
    await user.click(await screen.findByRole('button', { name: 'Menú de perfil de Ana' }));
    await user.click(screen.getByRole('menuitem', { name: 'Cambiar de perfil' }));
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(currentUrl(router)).toBe('/profiles');
    expect(localStorage.getItem(SESSION_KEY)).not.toBeNull();
  });

  it('"Cerrar sesión" clears the session and admin token and goes to the picker', async () => {
    const { user, router } = renderApp({ route: '/', profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await user.click(await screen.findByRole('button', { name: 'Menú de perfil de Esther' }));
    await user.click(screen.getByRole('menuitem', { name: 'Cerrar sesión' }));
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(currentUrl(router)).toBe('/profiles');
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(adminUnlock.getAdminToken()).toBeNull();
  });

  it('admins also get "Administración", which goes through the PIN gate', async () => {
    const { user, router } = renderApp({ route: '/', profile: f.adminProfile() });
    await user.click(await screen.findByRole('button', { name: 'Menú de perfil de Esther' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual([
      'Cambiar de perfil',
      'Administración',
      'Acerca de',
      'Cerrar sesión',
    ]);
    await user.click(screen.getByRole('menuitem', { name: 'Administración' }));
    await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
    await user.keyboard('1234');
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/scan'));
  });

  it('Esc closes the menu', async () => {
    const { user } = renderWithProviders(<AppShell />);
    await user.click(screen.getByRole('button', { name: 'Menú de perfil de Ana' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});

describe('Footer', () => {
  it('shows the TMDB logo and the Spanish attribution, linking to /about', () => {
    renderWithProviders(<AppShell />);
    const footer = screen.getByRole('contentinfo');
    const link = within(footer).getByRole('link');
    expect(link).toHaveAttribute('href', '/about');
    expect(within(footer).getByRole('img', { name: 'Logotipo de TMDB' })).toBeInTheDocument();
    expect(footer).toHaveTextContent(
      'Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB.',
    );
  });
});
