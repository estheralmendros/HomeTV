import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { renderWithProviders } from '../../tests/renderWithProviders';
import { getScan } from '@/api/admin';
import { adminUnlock } from './adminUnlock';
import { isAdminActionCancelled, useAdminAction } from './useAdminAction';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
});
afterEach(() => rec.dispose());

/** Runs `op` through useAdminAction on click and shows the outcome. */
function Harness({ op }: { op: () => Promise<unknown> }) {
  const run = useAdminAction();
  const [outcome, setOutcome] = useState('idle');
  return (
    <>
      <button
        type="button"
        onClick={() =>
          run(op, 'Escanear ahora').then(
            () => setOutcome('done'),
            (err: unknown) => setOutcome(isAdminActionCancelled(err) ? 'cancelled' : 'failed'),
          )
        }
      >
        run
      </button>
      <output>{outcome}</output>
    </>
  );
}

const typePin = async (user: ReturnType<typeof renderWithProviders>['user']) => {
  await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
  await user.keyboard('1234');
};

describe('useAdminAction', () => {
  it('runs at once when an admin token is held', async () => {
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await screen.findByText('done');
    expect(op).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(0);
  });

  it('opens the keypad when locked and runs the op after one unlock', async () => {
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    expect(op).not.toHaveBeenCalled();
    await typePin(user);
    await screen.findByText('done');
    expect(op).toHaveBeenCalledTimes(1);
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
    expect(rec.find('GET', '/api/admin/scan')[0]?.headers.get('x-admin-token')).toBe('admin-token-1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('treats an expired token as locked', async () => {
    adminUnlock.set({ admin_token: 'old', expires_at: '2020-01-01T00:00:00Z' });
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await screen.findByRole('dialog');
  });

  it('admin_locked from the op re-prompts and retries exactly once', async () => {
    let calls = 0;
    server.use(
      http.get('/api/admin/scan', () => {
        calls += 1;
        return calls === 1 ? apiError(401, 'admin_locked') : HttpResponse.json(f.scanStatus());
      }),
    );
    const op = vi.fn(() => getScan());
    const { user, router } = renderWithProviders(<Harness op={op} />, {
      profile: f.adminProfile(),
      adminUnlock: { admin_token: 'stale', expires_at: '2099-01-01T00:00:00Z' },
    });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await typePin(user);
    await screen.findByText('done');
    expect(op).toHaveBeenCalledTimes(2);
    expect(rec.find('GET', '/api/admin/scan').map((c) => c.headers.get('x-admin-token'))).toEqual(['stale', 'admin-token-1']);
    // Not sent to the picker.
    expect(router.state.location.pathname).toBe('/');
  });

  it('a second admin_locked after the retry fails the op (no loop)', async () => {
    server.use(http.get('/api/admin/scan', () => apiError(401, 'admin_locked')));
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile(), adminUnlock: f.unlockResponse() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await typePin(user);
    await screen.findByText('failed');
    expect(op).toHaveBeenCalledTimes(2);
  });

  it('cancel drops the op', async () => {
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await user.click(await screen.findByRole('button', { name: 'Cancelar' }));
    await screen.findByText('cancelled');
    expect(op).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('403 closes the keypad, refetches /api/me and toasts', async () => {
    server.use(http.post('/api/admin/unlock', () => apiError(403, 'forbidden')));
    const op = vi.fn(() => getScan());
    const { user } = renderWithProviders(<Harness op={op} />, { profile: f.adminProfile() });
    await user.click(screen.getByRole('button', { name: 'run' }));
    await typePin(user);
    await screen.findByText('cancelled');
    expect(screen.getByText('Solo para administradores.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(rec.find('GET', '/api/me')).toHaveLength(1));
    expect(op).not.toHaveBeenCalled();
  });
});
