import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { currentUrl, renderApp, renderWithProviders } from '../../tests/renderWithProviders';
import { setViewportWidth } from '../../tests/viewport';
import { adminUnlock } from '@/auth/adminUnlock';
import { SESSION_KEY } from '@/auth/session';
import { AppShell } from './AppShell';

describe('NavBar', () => {
  it('has the logo link and Inicio / Películas / Series / Mi lista / Actividad links', () => {
    renderWithProviders(<AppShell />);
    expect(screen.getByRole('link', { name: 'HomeTV, ir a Inicio' })).toHaveAttribute('href', '/');
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(within(nav).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['Inicio', '/'],
      ['Películas', '/films'],
      ['Series', '/shows'],
      ['Mi lista', '/watchlist'],
      ['Actividad', '/activity'],
    ]);
  });

  it('marks "Actividad" current on /activity', async () => {
    renderApp({ route: '/activity' });
    const nav = await screen.findByRole('navigation', { name: 'Navegación principal' });
    await screen.findByRole('heading', { level: 1, name: 'Actividad' });
    expect(within(nav).getByRole('link', { name: 'Actividad' })).toHaveAttribute('aria-current', 'page');
  });

  it('marks "Mi lista" current on /watchlist (desktop)', async () => {
    renderApp({ route: '/watchlist?type=films' });
    const nav = await screen.findByRole('navigation', { name: 'Navegación principal' });
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    expect(within(nav).getByRole('link', { name: 'Mi lista' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Series' })).not.toHaveAttribute('aria-current');
  });

  it('desktop (1280 px): links and the search field in the top bar, no icon link, no tab bar', () => {
    renderWithProviders(<AppShell />);
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Buscar títulos' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Buscar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Secciones' })).not.toBeInTheDocument();
  });

  it.each([720, 800, 959])('%s px: the five links and a "Buscar" icon link instead of the search field', (px) => {
    setViewportWidth(px);
    renderWithProviders(<AppShell />);
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    expect(within(nav).getAllByRole('link')).toHaveLength(5);
    expect(screen.queryByRole('searchbox', { name: 'Buscar títulos' })).not.toBeInTheDocument();
    const banner = screen.getByRole('banner');
    const icon = within(banner).getByRole('link', { name: 'Buscar' });
    expect(icon).toHaveAttribute('href', '/search');
    expect(icon.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryByRole('navigation', { name: 'Secciones' })).not.toBeInTheDocument();
  });

  it('960 px: the search field again', () => {
    setViewportWidth(960);
    renderWithProviders(<AppShell />);
    expect(screen.getByRole('searchbox', { name: 'Buscar títulos' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Buscar' })).not.toBeInTheDocument();
  });

  it('below 720 px: the bottom tab bar has the five sections; "Buscar" is an icon link in the top bar, just before the avatar', () => {
    setViewportWidth(719);
    renderWithProviders(<AppShell />);
    const tabs = screen.getByRole('navigation', { name: 'Secciones' });
    expect(within(tabs).getAllByRole('link').map((a) => a.textContent)).toEqual(['Inicio', 'Películas', 'Series', 'Mi lista', 'Actividad']);
    expect(within(tabs).getByRole('link', { name: 'Mi lista' })).toHaveAttribute('href', '/watchlist');
    expect(within(tabs).getByRole('link', { name: 'Actividad' })).toHaveAttribute('href', '/activity');
    expect(within(tabs).queryByRole('link', { name: 'Buscar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Navegación principal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('searchbox', { name: 'Buscar títulos' })).not.toBeInTheDocument();
    const banner = screen.getByRole('banner');
    const search = within(banner).getByRole('link', { name: 'Buscar' });
    expect(search).toHaveAttribute('href', '/search');
    expect(search.nextElementSibling).toContainElement(screen.getByRole('button', { name: 'Menú de perfil de Ana' }));
    expect(screen.getByRole('link', { name: 'HomeTV, ir a Inicio' })).toBeInTheDocument();
  });

  it('the "Buscar" icon link is marked current on /search', async () => {
    setViewportWidth(360);
    renderApp({ route: '/search' });
    await screen.findByRole('heading', { level: 1, name: 'Buscar' });
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Buscar' })).toHaveAttribute('aria-current', 'page');
  });

  it('360 px: five tabs, "Mi lista" goes to /watchlist and is marked current there', async () => {
    setViewportWidth(360);
    const { user, router } = renderApp({ route: '/' });
    const tabs = await screen.findByRole('navigation', { name: 'Secciones' });
    expect(within(tabs).getAllByRole('link').map((a) => a.textContent)).toEqual(['Inicio', 'Películas', 'Series', 'Mi lista', 'Actividad']);
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Buscar' })).toBeInTheDocument();
    await user.click(within(tabs).getByRole('link', { name: 'Mi lista' }));
    await screen.findByRole('heading', { level: 1, name: 'Mi lista · 0' });
    expect(currentUrl(router)).toBe('/watchlist');
    expect(within(tabs).getByRole('link', { name: 'Mi lista' })).toHaveAttribute('aria-current', 'page');
    expect(within(tabs).getByRole('link', { name: 'Inicio' })).not.toHaveAttribute('aria-current');
  });

  it('switches layouts when the viewport crosses 720 px', () => {
    renderWithProviders(<AppShell />);
    act(() => setViewportWidth(360));
    expect(screen.getByRole('navigation', { name: 'Secciones' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Navegación principal' })).not.toBeInTheDocument();
    act(() => setViewportWidth(720));
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Secciones' })).not.toBeInTheDocument();
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

describe('Tab bar spacer', () => {
  it('below 720 px the shell reserves room for the fixed tab bar', () => {
    setViewportWidth(360);
    const { container } = renderWithProviders(<AppShell />);
    expect(screen.getByRole('navigation', { name: 'Secciones' })).toBeInTheDocument();
    expect(container.querySelector('.shell')).toHaveClass('withTabBar');
  });

  it('desktop (1280 px): no tab bar, no spacer', () => {
    const { container } = renderWithProviders(<AppShell />);
    expect(container.querySelector('.shell')).not.toHaveClass('withTabBar');
  });

  it('follows viewport changes', () => {
    const { container } = renderWithProviders(<AppShell />);
    act(() => setViewportWidth(360));
    expect(container.querySelector('.shell')).toHaveClass('withTabBar');
  });
});

describe('No footer', () => {
  it('AppShell pages show no TMDB banner (the attribution lives on /about only)', () => {
    renderWithProviders(<AppShell />);
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Logotipo de TMDB' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Este producto utiliza la API de TMDB/)).not.toBeInTheDocument();
  });
});
