// Boot step 1: GET /api/info on every full page load. needs_bootstrap routes
// every path to /setup; /setup redirects to /profiles once bootstrapped (to / when this tab
// already holds a session, e.g. right after the bootstrap form signed in).
import { useQuery } from '@tanstack/react-query';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { errorMessage } from '@/api/errors';
import { infoQuery } from '@/api/queries';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenSpinner } from '@/components/Spinner';
import { useAuth } from './AuthProvider';

export function BootGate() {
  const info = useQuery(infoQuery());
  const { pathname } = useLocation();
  const { session } = useAuth();
  if (info.isPending) return <FullScreenSpinner />;
  if (info.isError) return <ErrorState message={errorMessage(info.error)} onRetry={() => void info.refetch()} />;
  if (info.data.needs_bootstrap && pathname !== '/setup') return <Navigate to="/setup" replace />;
  if (!info.data.needs_bootstrap && pathname === '/setup') return <Navigate to={session ? '/' : '/profiles'} replace />;
  return <Outlet />;
}
