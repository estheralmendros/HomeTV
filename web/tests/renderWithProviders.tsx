// Test harness: QueryClient (retry: false), data router (createMemoryRouter + RouterProvider,
// so errorElement and lazy work), AppProviders (Toast, AuthProvider, AdminPinProvider) with a
// seeded session.
//
//   renderWithProviders(<FilmDetailPage />, { path: '/films/:id', route: '/films/12' })
//   renderApp({ route: '/admin' })   // the real app route table
import type { QueryClient } from '@tanstack/react-query';
import { render, screen, type RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { RouterProvider, createMemoryRouter, useLocation, type RouteObject } from 'react-router-dom';
import { createQueryClient } from '@/api/queryClient';
import { queryKeys } from '@/api/queryKeys';
import type { Info, Profile, UnlockResponse } from '@/api/types';
import { AppProviders } from '@/AppProviders';
import { adminUnlock } from '@/auth/adminUnlock';
import { SESSION_KEY, type StoredSession } from '@/auth/session';
import { FullScreenSpinner } from '@/components/Spinner';
import { RouteErrorBoundary } from '@/lib/RouteErrorBoundary';
import { ROUTER_FUTURE, ROUTER_PROVIDER_FUTURE, appRoutes } from '@/router';
import * as f from './fixtures';

export interface ProviderOptions {
  /** Initial URL (default '/'). */
  route?: string;
  /** Extra history entries before `route` (e.g. to test navigate(-1)). */
  history?: string[];
  /**
   * The session in localStorage that AuthProvider reads once at boot. Default: a valid session
   * for `profile` (or profile 1). `null`: signed out.
   */
  session?: StoredSession | null;
  /** Seeds ['me'] (fresh for 5 min). Default f.profile(); null: not seeded (GET /api/me runs). */
  profile?: Profile | null;
  /** Seeds ['info'] (staleTime Infinity). Default f.info(); null: not seeded. */
  info?: Info | null;
  /** Pre-unlocked admin token (in memory). */
  adminUnlock?: UnlockResponse | null;
  queryClient?: QueryClient;
  /** user-event options, e.g. { advanceTimers: vi.advanceTimersByTime } with fake timers. */
  userOptions?: Parameters<typeof userEvent.setup>[0];
}

export interface ComponentOptions extends ProviderOptions {
  /** Route path of `ui` (default '*'). */
  path?: string;
  /** Additional routes next to `ui` (navigation targets). */
  routes?: RouteObject[];
  /** Child routes of `ui` (rendered by its <Outlet/>), e.g. for layouts and guards. */
  childRoutes?: RouteObject[];
}

export interface RenderWithProvidersResult extends RenderResult {
  router: ReturnType<typeof createMemoryRouter>;
  queryClient: QueryClient;
  user: ReturnType<typeof userEvent.setup>;
}

export function createTestQueryClient(): QueryClient {
  const client = createQueryClient();
  client.setDefaultOptions({
    ...client.getDefaultOptions(),
    queries: { ...client.getDefaultOptions().queries, retry: false },
    mutations: { retry: false },
  });
  return client;
}

/** Shows the current URL; the default catch-all route of renderWithProviders. */
export function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

/** Current URL (pathname + search) of a router. */
export function currentUrl(router: { state: { location: { pathname: string; search: string } } }): string {
  return router.state.location.pathname + router.state.location.search;
}

function mount(routes: RouteObject[], options: ProviderOptions): RenderWithProvidersResult {
  const profile = options.profile === undefined ? f.profile() : options.profile;
  const session =
    options.session === undefined ? f.session({ profile_id: profile?.id ?? 1, token: `token-${profile?.id ?? 1}` }) : options.session;
  if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  else localStorage.removeItem(SESSION_KEY);

  const queryClient = options.queryClient ?? createTestQueryClient();
  const info = options.info === undefined ? f.info() : options.info;
  if (info) queryClient.setQueryData(queryKeys.info(), info);
  if (profile && session) queryClient.setQueryData(queryKeys.me(), profile);
  if (options.adminUnlock) adminUnlock.set(options.adminUnlock);

  const router = createMemoryRouter(routes, {
    initialEntries: [...(options.history ?? []), options.route ?? '/'],
    future: ROUTER_FUTURE,
  });
  const user = userEvent.setup(options.userOptions);
  const result = render(
    <AppProviders queryClient={queryClient} navigate={(to) => void router.navigate(to)}>
      <RouterProvider router={router} future={ROUTER_PROVIDER_FUTURE} />
    </AppProviders>,
  );
  return { ...result, router, queryClient, user };
}

/** Renders `ui` at `path` inside the full provider stack (root errorElement included). */
export function renderWithProviders(ui: ReactElement, options: ComponentOptions = {}): RenderWithProvidersResult {
  const path = options.path ?? '*';
  const children: RouteObject[] = [{ path, element: ui, children: options.childRoutes }, ...(options.routes ?? [])];
  if (path !== '*') children.push({ path: '*', element: <LocationProbe /> });
  return mount([{ errorElement: <RouteErrorBoundary />, hydrateFallbackElement: <FullScreenSpinner />, children }], options);
}

/** Renders the real app route table at `route` (boot sequence, guards, lazy routes). */
export function renderApp(options: ProviderOptions = {}): RenderWithProvidersResult {
  return mount(appRoutes, options);
}

/** The URL shown by LocationProbe (after a navigation out of `ui`). */
export async function findProbedLocation(): Promise<string> {
  return (await screen.findByTestId('location')).textContent ?? '';
}
