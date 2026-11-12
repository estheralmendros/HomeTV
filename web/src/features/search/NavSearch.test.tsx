import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { currentUrl, renderApp } from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';

async function navSearch() {
  return screen.findByRole('searchbox', { name: 'Buscar títulos' });
}

describe('NavSearch: magnifier button', () => {
  it('the bar ends with a "Buscar" submit button that searches like Enter', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await navSearch();
    const form = input.closest('form')!;
    const button = within(form).getByRole('button', { name: 'Buscar' });
    expect(button).toHaveAttribute('type', 'submit');
    expect(button.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    await user.type(input, '  dune ');
    await user.click(button);
    await screen.findByRole('heading', { level: 1, name: 'Buscar' });
    expect(currentUrl(router)).toBe('/search?q=dune');
  });

  it.each([
    ['empty', ''],
    ['only spaces', '   '],
  ])('%s bar: neither the button nor Enter does anything', async (_label, text) => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await navSearch();
    if (text) await user.type(input, text);
    await user.click(within(input.closest('form')!).getByRole('button', { name: 'Buscar' }));
    await user.type(input, '{Enter}');
    expect(currentUrl(router)).toBe('/films');
  });

  it('below 960 px the /search page field has the button too', async () => {
    setViewportWidth(800);
    const { user, router } = renderApp({ route: '/search' });
    const input = await screen.findByRole('searchbox', { name: 'Buscar películas y series' });
    const button = within(input.closest('form')!).getByRole('button', { name: 'Buscar' });
    await user.click(button);
    expect(currentUrl(router)).toBe('/search');
    await user.type(input, 'dune');
    await user.click(button);
    await waitFor(() => expect(currentUrl(router)).toBe('/search?q=dune'));
  });
});

describe('NavSearch: cleared when switching screen', () => {
  async function searchDune(user: ReturnType<typeof renderApp>['user']) {
    const input = await navSearch();
    await user.type(input, 'dune{Enter}');
    await screen.findByRole('region', { name: 'Películas (1)' });
    return input;
  }

  it.each(['Inicio', 'Películas', 'Series', 'Mi lista', 'Actividad'])('the "%s" section link clears the bar', async (name) => {
    const { user } = renderApp({ route: '/films' });
    const input = await searchDune(user);
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    await user.click(within(nav).getByRole('link', { name }));
    await waitFor(() => expect(input).toHaveValue(''));
  });

  it('the logo clears the bar', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await searchDune(user);
    await user.click(screen.getByRole('link', { name: 'HomeTV, ir a Inicio' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(input).toHaveValue('');
  });

  it('a profile-menu entry ("Acerca de") clears the bar', async () => {
    const { user } = renderApp({ route: '/films' });
    const input = await searchDune(user);
    await user.click(screen.getByRole('button', { name: 'Menú de perfil de Ana' }));
    await user.click(screen.getByRole('menuitem', { name: 'Acerca de' }));
    await screen.findByRole('heading', { level: 1, name: 'Acerca de' });
    expect(input).toHaveValue('');
  });

  it('clicking the current section clears unsubmitted text', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await navSearch();
    await user.type(input, 'dun');
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    await user.click(within(nav).getByRole('link', { name: 'Películas' }));
    expect(input).toHaveValue('');
    expect(currentUrl(router)).toBe('/films');
  });

  it('opening a result and going back keeps the search', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await searchDune(user);
    await user.click(screen.getByRole('link', { name: 'Dune (2021)' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/films/12'));
    expect(input).toHaveValue('dune');
    await act(() => router.navigate(-1));
    await screen.findByRole('region', { name: 'Películas (1)' });
    expect(input).toHaveValue('dune');
  });

  it('browser back to the results after a section click refills the bar from the URL', async () => {
    const { user, router } = renderApp({ route: '/films' });
    const input = await searchDune(user);
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' });
    await user.click(within(nav).getByRole('link', { name: 'Inicio' }));
    await waitFor(() => expect(input).toHaveValue(''));
    await act(() => router.navigate(-1));
    await waitFor(() => expect(input).toHaveValue('dune'));
  });
});
