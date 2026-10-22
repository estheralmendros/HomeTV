import { useQuery } from '@tanstack/react-query';
import { meQuery } from '@/api/queries';
import type { Profile } from '@/api/types';
import { useAuth } from './AuthProvider';

/** `GET /api/me` for this tab's session (enabled only while signed in). */
export function useMeQuery() {
  const { session } = useAuth();
  return useQuery({ ...meQuery(), enabled: session !== null });
}

/** The current profile (null until /api/me resolved or when signed out). */
export function useCurrentProfile(): Profile | null {
  return useMeQuery().data ?? null;
}

export function useIsAdmin(): boolean {
  return useCurrentProfile()?.is_admin === true;
}
