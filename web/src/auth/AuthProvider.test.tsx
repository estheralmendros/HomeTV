import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { currentUrl, renderApp, renderWithProviders } from '../../tests/renderWithProviders';
import { getMe } from '@/api/profiles';
import { queryKeys } from '@/api/queryKeys';
import { putProgress } from '@/api/watch';
import { AppShell } from '@/components/AppShell';
import { DEFAULT_SUBTITLE_STYLE } from '@/api/subtitleStyleDefaults';
import { enqueueWrite, pendingChoice, rememberPick } from '@/player/trackChoiceSession';
import { reloader } from '@/lib/reload';
import { adminUnlock } from './adminUnlock';
import { useAuth } from './AuthProvider';
import { useMeQuery } from './useCurrentProfile';
import { SESSION_KEY } from './session';

const ana = f.profile({ id: 1, name: 'Ana' });
const kids = f.profile({ id: 2, name: 'Kids', avatar_id: 'avatar-04' });
const anaSession = f.session({ token: 'token-ana', profile_id: 1 });
const kidsSession = f.session({ token: 'token-kids', profile_id: 2 });

/** Another tab writes hometv.session: localStorage changes and this tab gets a storage event. */
function otherTabWrites(value: object | null) {
  const newValue = value === null ? null : JSON.stringify(value);
  if (newValue === null) localStorage.removeItem(SESSION_KEY);
  else localStorage.setItem(SESSION_KEY, newValue);
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', { key: SESSION_KEY, newValue }));
  });
}

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
  server.use(http.get('/api/profiles', () => HttpResponse.json({ items: [ana, kids] })));
});
afterEach(() => rec.dispose());

const BANNER = 'El perfil ha cambiado a Kids en otra pestaña. Esta pestaña sigue con Ana: recarga para cambiar.';

describe('AuthProvider: per-tab session', () => {
  it('reads the token from localStorage once at boot; another tab’s write does not change it', async () => {
    renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    otherTabWrites(kidsSession);
    await screen.findByText(BANNER);

    await getMe();
    await putProgress({ item_type: 'film', item_id: 12, media_file_id: 91, position_seconds: 10 }, { keepalive: true });
    const auth = rec.calls.filter((c) => c.url.pathname !== '/api/profiles').map((c) => c.headers.get('authorization'));
    expect(auth).toEqual(['Bearer token-ana', 'Bearer token-ana']);
  });

  it('names both profiles in the banner and reloads on [Recargar]', async () => {
    const reload = vi.spyOn(reloader, 'reload').mockImplementation(() => undefined);
    const { user } = renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    otherTabWrites(kidsSession);
    await screen.findByText(BANNER);
    await user.click(screen.getByRole('button', { name: 'Recargar' }));
    expect(reload).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Cerrar aviso' }));
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it('uses the cached profiles list when present (no request)', async () => {
    const { queryClient } = renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    queryClient.setQueryData(queryKeys.profiles(), { items: [ana, kids] });
    otherTabWrites(kidsSession);
    await screen.findByText(BANNER);
    expect(rec.find('GET', '/api/profiles')).toHaveLength(0);
  });

  it('falls back to "otro perfil" when the name cannot be resolved', async () => {
    server.use(http.get('/api/profiles', () => HttpResponse.error()));
    renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    otherTabWrites(kidsSession);
    await screen.findByText(
      'El perfil ha cambiado a otro perfil en otra pestaña. Esta pestaña sigue con Ana: recarga para cambiar.',
    );
  });

  it('ignores the same profile_id and a removed key', async () => {
    renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    otherTabWrites({ ...anaSession, token: 'token-ana-2' });
    otherTabWrites(null);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText(/en otra pestaña/)).not.toBeInTheDocument();
  });

  it('a fresh AuthProvider boots from the latest stored session', async () => {
    const first = renderWithProviders(<AppShell />, { session: anaSession, profile: ana });
    otherTabWrites(kidsSession);
    first.unmount();
    // New tab: no seeded session write, it reads what is stored (Kids).
    localStorage.setItem(SESSION_KEY, JSON.stringify(kidsSession));
    const { queryClient } = renderApp({ session: kidsSession, profile: null, route: '/' });
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.me())).toBeDefined());
    expect(rec.find('GET', '/api/me').at(-1)?.headers.get('authorization')).toBe('Bearer token-kids');
  });
});

describe('AuthProvider: 401 unauthorized', () => {
  it('clears the session, admin token and cache, then goes to /profiles?reason=expired', async () => {
    const { router, queryClient } = renderWithProviders(<AppShell />, {
      session: anaSession,
      profile: ana,
      adminUnlock: f.unlockResponse(),
      path: '/',
    });
    queryClient.setQueryData(queryKeys.home(), f.homeResponse());
    server.use(http.get('/api/me', () => apiError(401, 'unauthorized')));
    await expect(getMe()).rejects.toMatchObject({ code: 'unauthorized' });
    await waitFor(() => expect(currentUrl(router)).toBe('/profiles?reason=expired'));
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(adminUnlock.getAdminToken()).toBeNull();
    expect(queryClient.getQueryData(queryKeys.home())).toBeUndefined();
  });

  it('keeps a newer session stored by another tab', async () => {
    const { router } = renderWithProviders(<AppShell />, { session: anaSession, profile: ana, path: '/' });
    otherTabWrites(kidsSession);
    server.use(http.get('/api/me', () => apiError(401, 'unauthorized')));
    await expect(getMe()).rejects.toMatchObject({ code: 'unauthorized' });
    await waitFor(() => expect(currentUrl(router)).toBe('/profiles?reason=expired'));
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toEqual(kidsSession);
  });
});

describe('AuthProvider: sign out', () => {
  function SignOutButton() {
    const { signOut, session } = useAuth();
    return (
      <button type="button" onClick={signOut}>
        {session ? 'out' : 'none'}
      </button>
    );
  }

  it('clears the stored session and admin token and goes to /profiles', async () => {
    const { user, router } = renderWithProviders(<SignOutButton />, {
      session: anaSession,
      profile: ana,
      adminUnlock: f.unlockResponse(),
      path: '/',
    });
    await user.click(screen.getByRole('button', { name: 'out' }));
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(adminUnlock.getAdminToken()).toBeNull();
    await waitFor(() => expect(currentUrl(router)).toBe('/profiles'));
  });
});

describe('AuthProvider: ["me"] and the track choice session', () => {
  function Account() {
    const { signIn, signOut } = useAuth();
    const me = useMeQuery().data;
    return (
      <>
        <p>{me ? `${me.name}:${'subtitle_style' in me ? me.subtitle_style.color : '-'}` : 'none'}</p>
        <button type="button" onClick={() => signIn(f.sessionResponse(kids))}>
          kids
        </button>
        <button type="button" onClick={signOut}>
          out
        </button>
      </>
    );
  }

  it('sign-in seeds ["me"] with the default style, then refetches the real one', async () => {
    server.use(
      http.get('/api/me', ({ request }) =>
        HttpResponse.json({ ...f.meForToken(request.headers.get('authorization')), subtitle_style: f.subtitleStyle({ color: 'yellow' }) }),
      ),
    );
    const { user, queryClient } = renderWithProviders(<Account />, { session: anaSession, profile: ana, path: '/' });
    await user.click(screen.getByRole('button', { name: 'kids' }));
    await screen.findByText('Kids:yellow');
    expect(rec.find('GET', '/api/me').at(-1)?.headers.get('authorization')).toBe('Bearer token-2');
    expect(queryClient.getQueryData(queryKeys.me())).toMatchObject({ id: 2, subtitle_style: { color: 'yellow' } });
  });

  it('the seed itself is the profile + DEFAULT_SUBTITLE_STYLE (no observer: no refetch yet)', async () => {
    function SignInOnly() {
      const { signIn } = useAuth();
      return (
        <button type="button" onClick={() => signIn(f.sessionResponse(kids))}>
          kids
        </button>
      );
    }
    const { user, queryClient } = renderWithProviders(<SignInOnly />, { session: anaSession, profile: ana, path: '/' });
    await user.click(screen.getByRole('button', { name: 'kids' }));
    expect(queryClient.getQueryData(queryKeys.me())).toEqual({ ...kids, subtitle_style: DEFAULT_SUBTITLE_STYLE });
    expect(queryClient.getQueryState(queryKeys.me())?.isInvalidated).toBe(true);
  });

  it('registers the profile getter on the first render with a stored session (no signIn)', async () => {
    renderWithProviders(<p>page</p>, { session: anaSession, profile: ana, path: '/' });
    const send = vi.fn(() => Promise.resolve());
    await expect(enqueueWrite(1, 'film', 12, send)).resolves.toEqual({ outcome: 'ok' });
    await expect(enqueueWrite(2, 'film', 12, send)).resolves.toEqual({ outcome: 'dropped' });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('sign-in to another profile and sign-out clear the overrides', async () => {
    const { user } = renderWithProviders(<Account />, { session: anaSession, profile: ana, path: '/' });
    rememberPick(1, 'film', 12, f.trackChoice());
    await user.click(screen.getByRole('button', { name: 'kids' }));
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
    rememberPick(2, 'film', 12, f.trackChoice());
    await user.click(screen.getByRole('button', { name: 'out' }));
    expect(pendingChoice(2, 'film', 12)).toBeUndefined();
  });

  it('a 401 ends the session and clears the overrides', async () => {
    const { router } = renderWithProviders(<p>page</p>, { session: anaSession, profile: ana, path: '/' });
    rememberPick(1, 'film', 12, f.trackChoice());
    server.use(http.get('/api/me', () => apiError(401, 'unauthorized')));
    await expect(getMe()).rejects.toMatchObject({ code: 'unauthorized' });
    await waitFor(() => expect(currentUrl(router)).toBe('/profiles?reason=expired'));
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
  });
});
