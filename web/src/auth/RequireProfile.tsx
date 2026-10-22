// Boot steps 2–4: the in-memory session (read once at boot) must exist and not be expired,
// then GET /api/me. A 401 there goes through the global onUnauthorized handler.
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { errorMessage, isApiError } from '@/api/errors';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenSpinner } from '@/components/Spinner';
import { useAuth } from './AuthProvider';
import { isExpired } from './session';
import { useMeQuery } from './useCurrentProfile';

/** Location state the guard leaves for the picker, which returns there after a pick. */
export interface FromState {
  from?: string;
}

const EXPIRED = '/profiles?reason=expired';

export function RequireProfile() {
  const { session, endReason } = useAuth();
  const location = useLocation();
  const me = useMeQuery();
  const state: FromState = { from: location.pathname + location.search };

  // Same target as the 401 handler's navigation, whichever runs first.
  if (!session) return <Navigate to={endReason === 'expired' ? EXPIRED : '/profiles'} replace state={state} />;
  if (isExpired(session)) return <Navigate to={EXPIRED} replace state={state} />;
  if (me.isPending) return <FullScreenSpinner />;
  if (me.isError) {
    if (isApiError(me.error, 'unauthorized')) return <FullScreenSpinner />;
    return <ErrorState message={errorMessage(me.error)} onRetry={() => void me.refetch()} />;
  }
  return <Outlet />;
}
