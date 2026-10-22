import { useQuery } from '@tanstack/react-query';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { currentUrl, renderApp, renderWithProviders } from '../../tests/renderWithProviders';
import { getScan } from '@/api/admin';
import { queryKeys } from '@/api/queryKeys';
import { NavBar } from '@/components/NavBar';
import { RequireAdmin } from './RequireAdmin';
import { useAdminToken } from './useAdminAction';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
});
afterEach(() => rec.dispose());

describe('RequireAdmin', () => {
  it('a viewer opening /admin sees "Solo para administradores" and a link home (no keypad)', async () => {
    renderApp({ route: '/admin/scan' });
    expect(await screen.findByText('Solo para administradores')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a Inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(0);
  });

  it('viewers never see the Administración menu entry', async () => {
    const { user } = renderWithProviders(<NavBar />);
    await user.click(screen.getByRole('button', { name: 'Menú de perfil de Ana' }));
    expect(screen.queryByRole('menuitem', { name: 'Administración' })).not.toBeInTheDocument();
  });

  it('entering /admin locked → keypad → POST unlock → page renders', async () => {
    const { user, router } = renderApp({ route: '/admin', profile: f.adminProfile() });
    await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
    expect(screen.queryByRole('heading', { name: 'Administración' })).not.toBeInTheDocument();
    await user.keyboard('1234');
    await screen.findByRole('heading', { name: 'Administración' });
    await waitFor(() => expect(currentUrl(router)).toBe('/admin/scan'));
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
  });

  it('cancel on entry navigates back', async () => {
    const { user, router } = renderApp({ route: '/admin', history: ['/films'], profile: f.adminProfile() });
    await user.click(await screen.findByRole('button', { name: 'Cancelar' }));
    await screen.findByRole('heading', { name: 'Películas' });
    expect(currentUrl(router)).toBe('/films');
  });

  it('cancel on a deep link without history goes home', async () => {
    const { user, router } = renderApp({ route: '/admin/scan', profile: f.adminProfile() });
    await user.click(await screen.findByRole('button', { name: 'Cancelar' }));
    await screen.findByRole('heading', { name: 'Inicio' });
    expect(currentUrl(router)).toBe('/');
  });

  it('an admin query answered admin_locked re-prompts, then refetches with the new token', async () => {
    let calls = 0;
    server.use(
      http.get('/api/admin/scan', () => {
        calls += 1;
        return calls === 1 ? apiError(401, 'admin_locked') : HttpResponse.json(f.scanStatus());
      }),
    );
    function ScanProbe() {
      const token = useAdminToken();
      const scan = useQuery({ queryKey: queryKeys.admin.scan(), queryFn: () => getScan(), enabled: token !== null });
      return <p>{scan.data ? `scan ${scan.data.state}` : 'no scan'}</p>;
    }
    const { user } = renderWithProviders(<RequireAdmin />, {
      path: '/admin',
      route: '/admin',
      profile: f.adminProfile(),
      adminUnlock: { admin_token: 'stale', expires_at: '2099-01-01T00:00:00Z' },
      childRoutes: [{ index: true, element: <ScanProbe /> }],
    });
    // The query fails with admin_locked: the client drops the token and the keypad opens over the page.
    await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
    expect(screen.getByText('no scan')).toBeInTheDocument();
    await user.keyboard('1234');
    expect(await screen.findByText('scan completed')).toBeInTheDocument();
    expect(rec.find('GET', '/api/admin/scan').map((c) => c.headers.get('x-admin-token'))).toEqual(['stale', 'admin-token-1']);
  });
});
