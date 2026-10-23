import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { Profile } from '@/api/types';
import { adminUnlock } from '@/auth/adminUnlock';
import { SESSION_KEY } from '@/auth/session';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';

const ESTHER = f.adminProfile({ id: 1, name: 'Esther', avatar_id: 'avatar-05' });
const KIDS = f.profile({ id: 2, name: 'Kids', avatar_id: 'avatar-04' });
const JAVI = f.adminProfile({ id: 3, name: 'Javi', avatar_id: 'avatar-07' });

// GET /api/me answers the signed-in admin (refetched after editing yourself).
beforeEach(() => {
  server.use(http.get(`${API}/me`, () => HttpResponse.json(ESTHER)));
});

function serveProfiles(items: Profile[]) {
  server.use(http.get(`${API}/profiles`, () => HttpResponse.json({ items })));
}

function renderProfiles(options: ProviderOptions = {}) {
  return renderApp({ route: '/admin/profiles', profile: ESTHER, adminUnlock: f.unlockResponse(), ...options });
}

const row = (name: string) => screen.getByRole('listitem', { name });
const dialog = () => screen.getByRole('dialog');

async function openEdit(user: ReturnType<typeof renderProfiles>['user'], name: string) {
  await user.click(await screen.findByRole('button', { name: `Editar ${name}` }));
  return screen.findByRole('dialog', { name: 'Editar perfil' });
}

describe('ProfilesAdminPage', () => {
  it('lists the profiles with the Administrador tag and Editar / Eliminar', async () => {
    serveProfiles([ESTHER, KIDS]);
    renderProfiles();
    await screen.findByRole('listitem', { name: 'Esther' });
    expect(within(row('Esther')).getByText('Administrador')).toBeInTheDocument();
    expect(within(row('Kids')).queryByText('Administrador')).not.toBeInTheDocument();
    expect(within(row('Kids')).getByRole('button', { name: 'Editar Kids' })).toBeInTheDocument();
    expect(within(row('Kids')).getByRole('button', { name: 'Eliminar Kids' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nuevo perfil' })).toBeEnabled();
  });

  it('"Nuevo perfil" is disabled at 12 profiles, with the explanation', async () => {
    serveProfiles(Array.from({ length: 12 }, (_, i) => f.profile({ id: i + 1, name: `P${i + 1}`, is_admin: i === 0 })));
    renderProfiles();
    await screen.findByRole('listitem', { name: 'P12' });
    const create = screen.getByRole('button', { name: 'Nuevo perfil' });
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription('Se ha alcanzado el máximo de 12 perfiles.');
  });

  describe('create', () => {
    it('a viewer: POST /api/admin/profiles without pin; no PIN section; the list refetches', async () => {
      serveProfiles([ESTHER, KIDS]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Nuevo perfil' }));
      const form = await screen.findByRole('dialog', { name: 'Nuevo perfil' });
      expect(within(form).getByRole('checkbox', { name: 'Administrador' })).not.toBeChecked();
      expect(within(form).queryByText('PIN de administrador')).not.toBeInTheDocument();
      expect(within(form).queryByLabelText('PIN')).not.toBeInTheDocument();
      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), '  Javi ');
      await user.click(within(form).getByRole('radio', { name: 'Avatar 3' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      const post = rec.find('POST', '/api/admin/profiles');
      expect(post).toHaveLength(1);
      expect(post[0]!.body).toEqual({ name: 'Javi', avatar_id: 'avatar-03', is_admin: false });
      expect(post[0]!.headers.get('x-admin-token')).toBe('admin-token-1');
      // The initial load, then the refetch after the mutation.
      await waitFor(() => expect(rec.find('GET', '/api/profiles')).toHaveLength(2));
      // The PRD invalidation table (profilesChanged): ['profiles'] and ['me'].
      await waitFor(() => expect(rec.find('GET', '/api/me')).toHaveLength(1));
    });

    it('client validation: name, avatar, and a required, confirmed PIN for an admin', async () => {
      const rec = recordRequests();
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Nuevo perfil' }));
      const form = await screen.findByRole('dialog', { name: 'Nuevo perfil' });
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(within(form).getByText('El nombre debe tener entre 1 y 30 caracteres.')).toBeInTheDocument();
      expect(within(form).getByText('Elige un avatar.')).toBeInTheDocument();

      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), 'Javi');
      await user.click(within(form).getByRole('radio', { name: 'Avatar 7' }));
      await user.click(within(form).getByRole('checkbox', { name: 'Administrador' }));
      expect(within(form).getByText('PIN de administrador')).toBeInTheDocument();
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(within(form).getByText('El PIN debe tener 4 dígitos.')).toBeInTheDocument();
      await user.type(within(form).getByLabelText('PIN'), '4321');
      await user.type(within(form).getByLabelText('Confirmar'), '4322');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(within(form).getByText('Los PIN no coinciden.')).toBeInTheDocument();
      expect(rec.find('POST', '/api/admin/profiles')).toHaveLength(0);

      await user.clear(within(form).getByLabelText('Confirmar'));
      await user.type(within(form).getByLabelText('Confirmar'), '4321');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('POST', '/api/admin/profiles')[0]!.body).toEqual({
        name: 'Javi',
        avatar_id: 'avatar-07',
        is_admin: true,
        pin: '4321',
      });
    });

    it.each([
      ['name_taken', 409, 'Ya existe un perfil con ese nombre.'],
      ['profile_limit_reached', 409, 'Se ha alcanzado el máximo de 12 perfiles.'],
      ['pin_required', 422, 'Los perfiles de administrador necesitan un PIN de 4 dígitos.'],
    ])('%s is shown in the form', async (code, status, text) => {
      server.use(http.post(`${API}/admin/profiles`, () => apiError(status, code)));
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Nuevo perfil' }));
      const form = await screen.findByRole('dialog', { name: 'Nuevo perfil' });
      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), 'Kids');
      await user.click(within(form).getByRole('radio', { name: 'Avatar 1' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(await within(form).findByText(text)).toBeInTheDocument();
      expect(dialog()).toBeInTheDocument();
    });

    it('a 422 on name shows the catalog field message, never the server msg', async () => {
      server.use(
        http.post(`${API}/admin/profiles`, () =>
          apiError(422, 'validation_error', { details: { errors: [{ loc: ['body', 'name'], msg: 'String should have at most 30 characters' }] } }),
        ),
      );
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Nuevo perfil' }));
      const form = await screen.findByRole('dialog', { name: 'Nuevo perfil' });
      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), 'Kids');
      await user.click(within(form).getByRole('radio', { name: 'Avatar 1' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(await within(form).findByText('El nombre debe tener entre 1 y 30 caracteres.')).toBeInTheDocument();
      expect(within(form).queryByText(/String should/)).not.toBeInTheDocument();
    });

    it('a create answered 401 admin_locked re-prompts the keypad and retries once', async () => {
      let posts = 0;
      server.use(
        http.post(`${API}/admin/profiles`, () =>
          ++posts === 1 ? apiError(401, 'admin_locked') : HttpResponse.json(f.profile({ id: 3, name: 'Javi' }), { status: 201 }),
        ),
      );
      const rec = recordRequests();
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Nuevo perfil' }));
      const form = await screen.findByRole('dialog', { name: 'Nuevo perfil' });
      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), 'Javi');
      await user.click(within(form).getByRole('radio', { name: 'Avatar 1' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await user.keyboard('1234');
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(rec.find('POST', '/api/admin/profiles')).toHaveLength(2);
      expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
    });
  });

  describe('edit', () => {
    it('sends only the changed fields', async () => {
      serveProfiles([ESTHER, KIDS]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      const form = await openEdit(user, 'Kids');
      expect(within(form).getByRole('textbox', { name: 'Nombre' })).toHaveValue('Kids');
      expect(within(form).getByRole('radio', { name: 'Avatar 4' })).toBeChecked();
      expect(within(form).queryByText('PIN de administrador')).not.toBeInTheDocument();
      await user.clear(within(form).getByRole('textbox', { name: 'Nombre' }));
      await user.type(within(form).getByRole('textbox', { name: 'Nombre' }), 'Peques');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('PATCH', '/api/admin/profiles/2')[0]!.body).toEqual({ name: 'Peques' });
      // profilesChanged: ['profiles'] and ['me'] even when editing another profile.
      await waitFor(() => expect(rec.find('GET', '/api/profiles')).toHaveLength(2));
      await waitFor(() => expect(rec.find('GET', '/api/me')).toHaveLength(1));
    });

    it('no change: closes without a request', async () => {
      serveProfiles([ESTHER, KIDS]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      const form = await openEdit(user, 'Kids');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(rec.find('PATCH', /profiles/)).toHaveLength(0);
    });

    it('promote: PIN + confirm required, PATCH {is_admin: true, pin}', async () => {
      serveProfiles([ESTHER, KIDS]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      const form = await openEdit(user, 'Kids');
      await user.click(within(form).getByRole('checkbox', { name: 'Administrador' }));
      expect(within(form).queryByRole('radio', { name: 'Mantener el PIN actual' })).not.toBeInTheDocument();
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(within(form).getByText('El PIN debe tener 4 dígitos.')).toBeInTheDocument();
      await user.type(within(form).getByLabelText('PIN'), '1111');
      await user.type(within(form).getByLabelText('Confirmar'), '1111');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('PATCH', '/api/admin/profiles/2')[0]!.body).toEqual({ is_admin: true, pin: '1111' });
    });

    it('an admin: "Mantener el PIN actual" omits pin; "Cambiar el PIN" sends it', async () => {
      serveProfiles([ESTHER, KIDS, JAVI]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      let form = await openEdit(user, 'Javi');
      expect(within(form).getByRole('checkbox', { name: 'Administrador' })).toBeChecked();
      expect(within(form).getByRole('radio', { name: 'Mantener el PIN actual' })).toBeChecked();
      expect(within(form).queryByLabelText('PIN')).not.toBeInTheDocument();
      await user.click(within(form).getByRole('radio', { name: 'Avatar 2' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      form = await openEdit(user, 'Javi');
      await user.click(within(form).getByRole('radio', { name: 'Cambiar el PIN' }));
      await user.type(within(form).getByLabelText('PIN'), '2222');
      await user.type(within(form).getByLabelText('Confirmar'), '2222');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('PATCH', '/api/admin/profiles/3').map((c) => c.body)).toEqual([{ avatar_id: 'avatar-02' }, { pin: '2222' }]);
    });

    it('demote: the note, no PIN section, PATCH {is_admin: false}; last_admin is shown', async () => {
      serveProfiles([ESTHER, KIDS, JAVI]);
      let patches = 0;
      server.use(
        http.patch(`${API}/admin/profiles/:id`, () =>
          ++patches === 1 ? apiError(409, 'last_admin') : HttpResponse.json(f.profile({ id: 3, name: 'Javi' })),
        ),
      );
      const rec = recordRequests();
      const { user } = renderProfiles();
      const form = await openEdit(user, 'Javi');
      await user.click(within(form).getByRole('checkbox', { name: 'Administrador' }));
      expect(within(form).getByText('Se eliminará su PIN de administrador.')).toBeInTheDocument();
      expect(within(form).queryByText('PIN de administrador')).not.toBeInTheDocument();
      expect(within(form).queryByRole('radio', { name: 'Mantener el PIN actual' })).not.toBeInTheDocument();
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      expect(await within(form).findByText('Tiene que haber al menos un perfil de administrador.')).toBeInTheDocument();
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await rec.settled();
      expect(rec.find('PATCH', '/api/admin/profiles/3').map((c) => c.body)).toEqual([{ is_admin: false }, { is_admin: false }]);
    });

    it('editing your own PIN clears the admin token only: no re-login, the keypad re-locks admin', async () => {
      serveProfiles([ESTHER, KIDS]);
      server.use(http.patch(`${API}/admin/profiles/:id`, () => HttpResponse.json(ESTHER)));
      const rec = recordRequests();
      const { user, router } = renderProfiles();
      const form = await openEdit(user, 'Esther');
      await user.click(within(form).getByRole('radio', { name: 'Cambiar el PIN' }));
      await user.type(within(form).getByLabelText('PIN'), '9876');
      await user.type(within(form).getByLabelText('Confirmar'), '9876');
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await rec.settled();
      expect(rec.find('PATCH', '/api/admin/profiles/1')[0]!.body).toEqual({ pin: '9876' });
      expect(adminUnlock.getAdminToken()).toBeNull();
      expect(currentUrl(router)).toBe('/admin/profiles');
      expect(JSON.parse(localStorage.getItem(SESSION_KEY) ?? 'null')).toMatchObject({ token: 'token-1', profile_id: 1 });
      // Entering the new PIN unlocks again (no profile picker).
      await user.keyboard('9876');
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(screen.getByRole('listitem', { name: 'Esther' })).toBeInTheDocument();
    });

    it('demoting yourself clears the admin token, updates ["me"] and goes Home', async () => {
      serveProfiles([ESTHER, KIDS, JAVI]);
      server.use(http.patch(`${API}/admin/profiles/:id`, () => HttpResponse.json({ ...ESTHER, is_admin: false })));
      server.use(http.get(`${API}/me`, () => HttpResponse.json({ ...ESTHER, is_admin: false })));
      const { user, router, queryClient } = renderProfiles();
      const form = await openEdit(user, 'Esther');
      await user.click(within(form).getByRole('checkbox', { name: 'Administrador' }));
      await user.click(within(form).getByRole('button', { name: 'Guardar' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/'));
      expect(adminUnlock.getAdminToken()).toBeNull();
      expect(queryClient.getQueryData<Profile>(queryKeys.me())?.is_admin).toBe(false);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(localStorage.getItem(SESSION_KEY)).not.toBeNull();
    });
  });

  describe('delete', () => {
    it('asks for confirmation, then DELETE and refetch', async () => {
      serveProfiles([ESTHER, KIDS]);
      const rec = recordRequests();
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Eliminar Kids' }));
      const confirm = await screen.findByRole('dialog', { name: '¿Eliminar Kids?' });
      expect(within(confirm).getByText('Se perderá su historial de reproducción.')).toBeInTheDocument();
      await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }));
      expect(rec.find('DELETE', /profiles/)).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: 'Eliminar Kids' }));
      await user.click(within(await screen.findByRole('dialog', { name: '¿Eliminar Kids?' })).getByRole('button', { name: 'Eliminar' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(rec.find('DELETE', '/api/admin/profiles/2')).toHaveLength(1);
      expect(rec.find('DELETE', '/api/admin/profiles/2')[0]!.headers.get('x-admin-token')).toBe('admin-token-1');
      // The initial load, then the refetch after the mutation.
      await waitFor(() => expect(rec.find('GET', '/api/profiles')).toHaveLength(2));
    });

    it('last_admin is shown in the confirmation dialog', async () => {
      server.use(http.delete(`${API}/admin/profiles/:id`, () => apiError(409, 'last_admin')));
      const { user } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Eliminar Esther' }));
      const confirm = await screen.findByRole('dialog', { name: '¿Eliminar Esther?' });
      await user.click(within(confirm).getByRole('button', { name: 'Eliminar' }));
      expect(await within(confirm).findByText('Tiene que haber al menos un perfil de administrador.')).toBeInTheDocument();
    });

    it('deleting your own profile signs out and goes to the picker', async () => {
      serveProfiles([ESTHER, JAVI]);
      const { user, router } = renderProfiles();
      await user.click(await screen.findByRole('button', { name: 'Eliminar Esther' }));
      await user.click(within(await screen.findByRole('dialog', { name: '¿Eliminar Esther?' })).getByRole('button', { name: 'Eliminar' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/profiles'));
      expect(localStorage.getItem(SESSION_KEY)).toBeNull();
      expect(adminUnlock.getAdminToken()).toBeNull();
    });
  });

  it('a list error shows ErrorState with [Reintentar] that refetches', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/profiles`, () =>
        ++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json({ items: [ESTHER] }),
      ),
    );
    const { user } = renderProfiles();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('button', { name: 'Editar Esther' })).toBeInTheDocument();
  });
});
