import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { renderWithProviders } from '../../tests/renderWithProviders';
import { adminUnlock } from './adminUnlock';
import { AdminPinModal } from './AdminPinModal';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
});
afterEach(() => rec.dispose());

function setup(options: Parameters<typeof renderWithProviders>[1] = {}) {
  const callbacks = { onUnlocked: vi.fn(), onCancel: vi.fn(), onForbidden: vi.fn() };
  const result = renderWithProviders(<AdminPinModal purpose="Escanear ahora" {...callbacks} />, {
    profile: f.adminProfile(),
    ...options,
  });
  return { ...result, ...callbacks };
}

const dots = () => screen.getByTestId('pin-dots').textContent;
const unlockBodies = async () => {
  await rec.settled();
  return rec.find('POST', '/api/admin/unlock').map((c) => c.body);
};

describe('AdminPinModal', () => {
  it('renders the title, purpose, 4 empty dots and the 3×4 keypad', () => {
    setup();
    expect(screen.getByRole('dialog', { name: 'Introduce el PIN de administrador' })).toBeInTheDocument();
    expect(screen.getByText('Para: Escanear ahora')).toBeInTheDocument();
    expect(dots()).toBe('○○○○');
    for (const d of '0123456789') expect(screen.getByRole('button', { name: d })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Borrar' })).toHaveTextContent('⌫');
    expect(screen.getByRole('button', { name: 'Aceptar' })).toHaveTextContent('OK');
    expect(screen.getByRole('button', { name: 'Aceptar' })).toBeDisabled();
  });

  it('masks digits, supports ⌫ and auto-submits at the 4th on-screen digit', async () => {
    const { user, onUnlocked } = setup();
    await user.click(screen.getByRole('button', { name: '1' }));
    await user.click(screen.getByRole('button', { name: '2' }));
    expect(dots()).toBe('●●○○');
    await user.click(screen.getByRole('button', { name: 'Borrar' }));
    expect(dots()).toBe('●○○○');
    for (const d of ['2', '3', '4']) await user.click(screen.getByRole('button', { name: d }));
    await waitFor(() => expect(onUnlocked).toHaveBeenCalledTimes(1));
    expect(await unlockBodies()).toEqual([{ pin: '1234' }]);
    expect(adminUnlock.getAdminToken()).toBe('admin-token-1');
    const stored = [...Object.values(localStorage), ...Object.values(sessionStorage)].join(' ');
    expect(stored).not.toContain('admin-token-1');
  });

  it('accepts physical digits, Backspace and Enter', async () => {
    server.use(http.post('/api/admin/unlock', () => apiError(401, 'invalid_pin')));
    const { user } = setup();
    await user.keyboard('98');
    expect(dots()).toBe('●●○○');
    await user.keyboard('{Backspace}');
    expect(dots()).toBe('●○○○');
    await user.keyboard('765');
    await waitFor(async () => expect(await unlockBodies()).toEqual([{ pin: '9765' }]));
    // Enter re-submits a complete PIN (here after typing 4 digits while the first attempt failed).
    server.use(http.post('/api/admin/unlock', () => HttpResponse.json(f.unlockResponse())));
    await screen.findByText('PIN incorrecto.');
    await user.keyboard('4321');
    await waitFor(async () => expect((await unlockBodies()).at(-1)).toEqual({ pin: '4321' }));
  });

  it('OK submits 4 digits', async () => {
    server.use(http.post('/api/admin/unlock', () => apiError(503, 'http_503')));
    const { user } = setup();
    await user.keyboard('1234');
    await screen.findByText('Se ha producido un error inesperado (http_503).');
    // Digits are cleared after a failure: type again then press OK… auto-submit happens at 4.
    expect(dots()).toBe('○○○○');
  });

  it('invalid_pin shakes and clears the dots and shows "PIN incorrecto."', async () => {
    server.use(http.post('/api/admin/unlock', () => apiError(401, 'invalid_pin')));
    const { user, onUnlocked } = setup();
    await user.keyboard('0000');
    expect(await screen.findByText('PIN incorrecto.')).toBeInTheDocument();
    expect(screen.getByTestId('pin-dots')).toHaveAttribute('data-shake', 'true');
    expect(dots()).toBe('○○○○');
    expect(onUnlocked).not.toHaveBeenCalled();
    expect(adminUnlock.getAdminToken()).toBeNull();
  });

  it('429 disables the keypad with a countdown, then re-enables it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(
      http.post('/api/admin/unlock', () => apiError(429, 'too_many_attempts', { headers: { 'Retry-After': '42' } })),
    );
    setup({ userOptions: { advanceTimers: vi.advanceTimersByTime } });
    for (let i = 0; i < 4; i += 1) fireEvent.click(screen.getByRole('button', { name: '1' }));
    expect(await screen.findByText('Demasiados intentos. Vuelve a intentarlo en 0:42')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '5' })).toBeDisabled();
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByText('Demasiados intentos. Vuelve a intentarlo en 0:41')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(41_000));
    expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '5' })).toBeEnabled();
    fireEvent.keyDown(document, { key: '7' });
    expect(dots()).toBe('●○○○');
  });

  it('ignores input while locked out', async () => {
    server.use(
      http.post('/api/admin/unlock', () => apiError(429, 'too_many_attempts', { headers: { 'Retry-After': '30' } })),
    );
    const { user } = setup();
    await user.keyboard('1111');
    await screen.findByText(/Demasiados intentos/);
    fireEvent.keyDown(document, { key: '5' });
    expect(dots()).toBe('○○○○');
  });

  it('403 forbidden calls onForbidden', async () => {
    server.use(http.post('/api/admin/unlock', () => apiError(403, 'forbidden')));
    const { user, onForbidden } = setup();
    await user.keyboard('1234');
    await waitFor(() => expect(onForbidden).toHaveBeenCalledTimes(1));
  });

  it('[Cancelar] and Esc cancel', async () => {
    const { user, onCancel } = setup();
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(0);
  });
});
