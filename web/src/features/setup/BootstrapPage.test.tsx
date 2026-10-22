import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp, type RenderWithProvidersResult } from '../../../tests/renderWithProviders';
import { queryKeys } from '@/api/queryKeys';
import { SESSION_KEY } from '@/auth/session';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
  server.use(http.get('/api/info', () => HttpResponse.json(f.info({ needs_bootstrap: true }))));
});
afterEach(() => rec.dispose());

async function renderSetup(): Promise<RenderWithProvidersResult> {
  const result = renderApp({ route: '/setup', session: null, profile: null, info: f.info({ needs_bootstrap: true }) });
  await screen.findByRole('heading', { name: 'Bienvenido a HomeTV' });
  return result;
}

async function fill(r: RenderWithProvidersResult, { name = 'Esther', pin = '1234', confirm = '1234', avatar = 5 } = {}) {
  if (name) await r.user.type(screen.getByLabelText('Nombre'), name);
  if (avatar) await r.user.click(screen.getByRole('radio', { name: `Avatar ${avatar}` }));
  if (pin) await r.user.type(screen.getByLabelText('PIN de administrador'), pin);
  if (confirm) await r.user.type(screen.getByLabelText('Confirmar PIN'), confirm);
}

const submit = (r: RenderWithProvidersResult) => r.user.click(screen.getByRole('button', { name: 'Crear perfil' }));

describe('BootstrapPage', () => {
  it('shows the PIN helper text and numeric PIN inputs', async () => {
    await renderSetup();
    expect(
      screen.getByText('Introducirás este PIN cuando uses las funciones de administración. No hace falta para entrar.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('PIN de administrador')).toHaveAttribute('inputmode', 'numeric');
    expect(screen.getByLabelText('Confirmar PIN')).toHaveAttribute('inputmode', 'numeric');
    expect(screen.getAllByRole('radio')).toHaveLength(12);
  });

  it('requires name, avatar and a 4-digit PIN (no request)', async () => {
    const r = await renderSetup();
    await submit(r);
    expect(screen.getByText('El nombre debe tener entre 1 y 30 caracteres.')).toBeInTheDocument();
    expect(screen.getByText('Elige un avatar.')).toBeInTheDocument();
    expect(screen.getByText('El PIN debe tener 4 dígitos.')).toBeInTheDocument();
    expect(rec.find('POST', '/api/profiles/bootstrap')).toHaveLength(0);
  });

  it('requires the PIN confirmation to match', async () => {
    const r = await renderSetup();
    await fill(r, { confirm: '4321' });
    await submit(r);
    expect(screen.getByText('Los PIN no coinciden.')).toBeInTheDocument();
    expect(rec.find('POST', '/api/profiles/bootstrap')).toHaveLength(0);
  });

  it('creates the admin, stores the session and lands on Home', async () => {
    const r = await renderSetup();
    server.use(http.get('/api/info', () => HttpResponse.json(f.info())));
    await fill(r, { name: '  Esther ' });
    await submit(r);
    await screen.findByRole('heading', { name: 'Inicio' });
    expect(currentUrl(r.router)).toBe('/');
    await rec.settled();
    expect(rec.find('POST', '/api/profiles/bootstrap')[0]?.body).toEqual({ name: 'Esther', avatar_id: 'avatar-05', pin: '1234' });
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toMatchObject({ token: 'token-1', profile_id: 1 });
    expect(r.queryClient.getQueryData(queryKeys.me())).toMatchObject({ name: 'Esther', is_admin: true });
  });

  it('409 already_bootstrapped → /profiles', async () => {
    const r = await renderSetup();
    server.use(
      http.post('/api/profiles/bootstrap', () => apiError(409, 'already_bootstrapped')),
      http.get('/api/info', () => HttpResponse.json(f.info())),
    );
    await fill(r);
    await submit(r);
    await screen.findByRole('heading', { name: '¿Quién está viendo?' });
    expect(currentUrl(r.router)).toBe('/profiles');
  });

  it('422 shows the catalog message under the matching field, never the server msg', async () => {
    const r = await renderSetup();
    server.use(
      http.post('/api/profiles/bootstrap', () =>
        apiError(422, 'validation_error', { details: { errors: [{ loc: ['body', 'name'], msg: 'String too long' }] } }),
      ),
    );
    await fill(r);
    await submit(r);
    expect(await screen.findByText('El nombre debe tener entre 1 y 30 caracteres.')).toBeInTheDocument();
    expect(screen.queryByText(/String too long/)).not.toBeInTheDocument();
  });

  it('422 without a known field shows the form-level message', async () => {
    const r = await renderSetup();
    server.use(
      http.post('/api/profiles/bootstrap', () =>
        apiError(422, 'validation_error', { details: { errors: [{ loc: ['body'], msg: 'bad' }] } }),
      ),
    );
    await fill(r);
    await submit(r);
    expect(await screen.findByText('Revisa los datos introducidos.')).toBeInTheDocument();
  });

  it('keeps the form usable after a network error', async () => {
    const r = await renderSetup();
    server.use(http.post('/api/profiles/bootstrap', () => HttpResponse.error()));
    await fill(r);
    await submit(r);
    expect(await screen.findByText('No se puede conectar con el servidor.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Crear perfil' })).toBeEnabled());
  });
});
