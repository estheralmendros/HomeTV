// Per-tab profile identity: the session is read from localStorage ONCE at boot into
// memory; every request of this tab uses that token. Another tab's switch only shows a banner.
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { setTokenGetter, setUnauthorizedHandler } from '@/api/client';
import { profilesQuery } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import { DEFAULT_SUBTITLE_STYLE } from '@/api/subtitleStyleDefaults';
import type { Me, Profile, ProfilesResponse, SessionResponse } from '@/api/types';
import { setActiveProfile, setProfileGetter } from '@/player/trackChoiceSession';
import { S } from '@/strings.es';
import { adminUnlock } from './adminUnlock';
import {
  SESSION_KEY,
  clearSessionIfToken,
  parseSession,
  readSession,
  sessionFromResponse,
  writeLastProfileId,
  writeSession,
  type StoredSession,
} from './session';

export interface CrossTabNotice {
  /** The profile chosen in the other tab. */
  name: string;
  /** This tab's profile. */
  current: string;
}

export interface AuthContextValue {
  /** This tab's in-memory session (null: signed out). */
  session: StoredSession | null;
  /** Why the session ended in this tab: 'expired' after a 401 (the picker shows the banner). */
  endReason: 'expired' | null;
  /**
   * Stores a new session (picker, bootstrap): localStorage + memory in the same step, clears the
   * admin token and, unless `clearCache` is false, the per-profile query cache.
   */
  signIn: (response: SessionResponse, options?: { clearCache?: boolean }) => void;
  /** Client-side sign out (the API has no revocation endpoint), then /profiles. */
  signOut: () => void;
  crossTabNotice: CrossTabNotice | null;
  dismissCrossTabNotice: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * `queryClient.clear()` for a profile change, keeping `['info']` (server info is not
 * per-profile, so keeping it avoids a boot spinner).
 */
export function clearProfileQueries(queryClient: QueryClient): void {
  const info = queryClient.getQueryData(queryKeys.info());
  queryClient.clear();
  if (info !== undefined) queryClient.setQueryData(queryKeys.info(), info);
}

export interface AuthProviderProps {
  children: ReactNode;
  /** Router navigation (AuthProvider sits outside RouterProvider). */
  navigate: (to: string) => void;
}

export function AuthProvider({ children, navigate }: AuthProviderProps) {
  const queryClient = useQueryClient();
  const [session, setSessionState] = useState<StoredSession | null>(readSession);
  const sessionRef = useRef(session);
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;
  const [crossTabNotice, setCrossTabNotice] = useState<CrossTabNotice | null>(null);
  const [endReason, setEndReason] = useState<'expired' | null>(null);

  const setSession = useCallback((next: StoredSession | null) => {
    sessionRef.current = next;
    setSessionState(next);
  }, []);

  const endSession = useCallback(
    (reason: 'expired' | null) => {
      const current = sessionRef.current;
      if (current) clearSessionIfToken(current.token);
      setSession(null);
      setEndReason(reason);
      adminUnlock.clear();
      setCrossTabNotice(null);
      setActiveProfile(null);
      clearProfileQueries(queryClient);
      navigateRef.current(reason === 'expired' ? '/profiles?reason=expired' : '/profiles');
    },
    [queryClient, setSession],
  );

  // The getters read the ref, so registering them during the first render is safe; children may
  // build tokenized URLs (withToken) while rendering, before any effect runs. The profile getter
  // is the track choice write queue's send-time check (right after a reload too: no signIn ran).
  const getterRegistered = useRef(false);
  if (!getterRegistered.current) {
    getterRegistered.current = true;
    setTokenGetter(() => sessionRef.current?.token);
    setProfileGetter(() => sessionRef.current?.profile_id ?? null);
  }

  // Layout effect: (re)registered before any child's passive effect can start a query.
  useLayoutEffect(() => {
    setTokenGetter(() => sessionRef.current?.token);
    setProfileGetter(() => sessionRef.current?.profile_id ?? null);
    setUnauthorizedHandler(() => endSession('expired'));
    return () => {
      setTokenGetter(() => null);
      setProfileGetter(() => null);
      setUnauthorizedHandler(null);
    };
  }, [endSession]);

  const signIn = useCallback(
    (response: SessionResponse, { clearCache = true }: { clearCache?: boolean } = {}) => {
      const next = sessionFromResponse(response);
      writeSession(next);
      writeLastProfileId(next.profile_id);
      setSession(next);
      setEndReason(null);
      adminUnlock.clear();
      setCrossTabNotice(null);
      setActiveProfile(next.profile_id);
      if (clearCache) clearProfileQueries(queryClient);
      // The session answer has no style: seed the default, then load the real one.
      queryClient.setQueryData<Me>(queryKeys.me(), { ...response.profile, subtitle_style: DEFAULT_SUBTITLE_STYLE });
      void queryClient.invalidateQueries({ queryKey: queryKeys.me() });
    },
    [queryClient, setSession],
  );

  const signOut = useCallback(() => endSession(null), [endSession]);

  useEffect(() => {
    let disposed = false;

    const profileName = async (id: number): Promise<string | null> => {
      const cached = queryClient.getQueryData<ProfilesResponse>(queryKeys.profiles());
      const fromCache = cached?.items.find((p) => p.id === id)?.name;
      if (fromCache) return fromCache;
      try {
        const fetched = await queryClient.fetchQuery(profilesQuery());
        return fetched.items.find((p) => p.id === id)?.name ?? null;
      } catch {
        return null;
      }
    };

    const onStorage = async (event: StorageEvent) => {
      if (event.key !== SESSION_KEY) return;
      const next = parseSession(event.newValue);
      const current = sessionRef.current;
      if (!next || !current || next.profile_id === current.profile_id) return;
      const me = queryClient.getQueryData<Profile>(queryKeys.me());
      const [name, currentName] = await Promise.all([
        profileName(next.profile_id),
        me?.id === current.profile_id ? Promise.resolve(me.name) : profileName(current.profile_id),
      ]);
      if (disposed || sessionRef.current?.profile_id !== current.profile_id) return;
      setCrossTabNotice({ name: name ?? S.crossTab.otherProfile, current: currentName ?? S.crossTab.otherProfile });
    };

    const listener = (event: StorageEvent) => void onStorage(event);
    window.addEventListener('storage', listener);
    return () => {
      disposed = true;
      window.removeEventListener('storage', listener);
    };
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      endReason,
      signIn,
      signOut,
      crossTabNotice,
      dismissCrossTabNotice: () => setCrossTabNotice(null),
    }),
    [session, endReason, signIn, signOut, crossTabNotice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
