import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { API } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';

function renderAdmin(route: string, options: ProviderOptions = {}) {
  return renderApp({ route, profile: f.adminProfile(), adminUnlock: f.unlockResponse(), ...options });
}

const tabs = () => screen.getByRole('navigation', { name: 'Secciones de administración' });

describe('AdminLayout', () => {
  it('/admin redirects to /admin/scan and shows the three tabs', async () => {
    const { router } = renderAdmin('/admin');
    await screen.findByRole('heading', { level: 1, name: 'Administración' });
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/scan'));
    // The active tab re-renders after the redirect's URL change; wait for it rather than racing it.
    await waitFor(() => expect(within(tabs()).getByRole('link', { name: 'Escaneo' })).toHaveAttribute('aria-current', 'page'));
    expect(within(tabs()).getByRole('link', { name: /^Sin identificar/ })).toHaveAttribute('href', '/admin/unmatched');
    expect(within(tabs()).getByRole('link', { name: 'Perfiles' })).toHaveAttribute('href', '/admin/profiles');
  });

  it('the Unmatched tab badge shows ScanStatus.unmatched_count without an unmatched list request', async () => {
    const rec = recordRequests();
    renderAdmin('/admin/profiles');
    const tab = await within(await screen.findByRole('navigation', { name: 'Secciones de administración' })).findByRole('link', {
      name: 'Sin identificar 4',
    });
    expect(within(tab).getByText('4')).toBeInTheDocument();
    expect(rec.find('GET', '/api/admin/scan')).toHaveLength(1);
    expect(rec.find('GET', '/api/admin/unmatched')).toHaveLength(0);
  });

  it('the badge is hidden when unmatched_count is 0', async () => {
    server.use(http.get(`${API}/admin/scan`, () => HttpResponse.json(f.scanStatus({ unmatched_count: 0 }))));
    const rec = recordRequests();
    renderAdmin('/admin/profiles');
    await screen.findByRole('heading', { level: 1, name: 'Administración' });
    await waitFor(() => expect(rec.find('GET', '/api/admin/scan')).toHaveLength(1));
    await rec.settled();
    expect(within(tabs()).getByRole('link', { name: 'Sin identificar' })).toBeInTheDocument();
  });

  it('navigating between tabs', async () => {
    const { user, router } = renderAdmin('/admin/scan');
    await user.click(await within(await screen.findByRole('navigation', { name: 'Secciones de administración' })).findByRole('link', { name: 'Perfiles' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/profiles'));
    expect(within(tabs()).getByRole('link', { name: 'Perfiles' })).toHaveAttribute('aria-current', 'page');
  });
});
