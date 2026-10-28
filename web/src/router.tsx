// Route table (prd2 Routes). /admin/* and /watch/* are lazy route modules (separate chunks);
// the root errorElement handles stale chunks and unexpected errors (§13).
import { Navigate, createBrowserRouter, type RouteObject } from 'react-router-dom';
import { BootGate } from '@/auth/BootGate';
import { RequireAdmin } from '@/auth/RequireAdmin';
import { RequireProfile } from '@/auth/RequireProfile';
import { AppShell } from '@/components/AppShell';
import { NotFound } from '@/components/NotFound';
import { FullScreenSpinner } from '@/components/Spinner';
import { AboutPage } from '@/features/about/AboutPage';
import { FilmDetailPage } from '@/features/film/FilmDetailPage';
import { HomePage } from '@/features/home/HomePage';
import { LibraryPage } from '@/features/library/LibraryPage';
import { PersonPage } from '@/features/person/PersonPage';
import { ProfilePickerPage } from '@/features/profiles/ProfilePickerPage';
import { SearchPage } from '@/features/search/SearchPage';
import { BootstrapPage } from '@/features/setup/BootstrapPage';
import { ShowDetailPage } from '@/features/show/ShowDetailPage';
import { lazyRoute } from '@/lib/lazyRoute';
import { RouteErrorBoundary } from '@/lib/RouteErrorBoundary';

export const adminRoutes: RouteObject[] = [
  {
    lazy: lazyRoute(() => import('@/features/admin/AdminLayout'), (m) => m.AdminLayout),
    children: [
      { index: true, element: <Navigate to="scan" replace /> },
      { path: 'scan', lazy: lazyRoute(() => import('@/features/admin/ScanPage'), (m) => m.ScanPage) },
      { path: 'libraries', lazy: lazyRoute(() => import('@/features/admin/LibrariesPage'), (m) => m.LibrariesPage) },
      { path: 'unmatched', lazy: lazyRoute(() => import('@/features/admin/UnmatchedPage'), (m) => m.UnmatchedPage) },
      {
        path: 'profiles',
        lazy: lazyRoute(() => import('@/features/admin/ProfilesAdminPage'), (m) => m.ProfilesAdminPage),
      },
    ],
  },
];

export const appRoutes: RouteObject[] = [
  {
    path: '/',
    element: <BootGate />,
    errorElement: <RouteErrorBoundary />,
    // Shown while an initially matched lazy route (deep link to /admin or /watch) loads.
    hydrateFallbackElement: <FullScreenSpinner />,
    children: [
      { path: 'setup', element: <BootstrapPage /> },
      { path: 'profiles', element: <ProfilePickerPage /> },
      {
        element: <RequireProfile />,
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, element: <HomePage /> },
              { path: 'films', element: <LibraryPage type="films" /> },
              { path: 'shows', element: <LibraryPage type="shows" /> },
              { path: 'search', element: <SearchPage /> },
              { path: 'films/:id', element: <FilmDetailPage /> },
              { path: 'shows/:id', element: <ShowDetailPage /> },
              { path: 'people/:id', element: <PersonPage /> },
              { path: 'about', element: <AboutPage /> },
              { path: 'admin', element: <RequireAdmin />, children: adminRoutes },
            ],
          },
          {
            path: 'watch/:type/:id',
            lazy: lazyRoute(() => import('@/player/PlayerRoute'), (m) => m.PlayerRoute),
          },
        ],
      },
      { path: '*', element: <NotFound /> },
    ],
  },
];

/** React Router v7 behaviours, opted in early (also used by the test memory router). */
export const ROUTER_FUTURE = {
  v7_relativeSplatPath: true,
  v7_fetcherPersist: true,
  v7_normalizeFormMethod: true,
  v7_partialHydration: true,
  v7_skipActionErrorRevalidation: true,
} as const;

export const ROUTER_PROVIDER_FUTURE = { v7_startTransition: true } as const;

export function createAppRouter() {
  return createBrowserRouter(appRoutes, { future: ROUTER_FUTURE });
}
