// The single owner of "¿Qué te ha parecido?". Sits above the routes (in
// AppProviders), so a prompt survives the navigation that leaves the player; the player
// suppresses it while mounted. One pending prompt at a time (a newer offer replaces an unopened
// one); a title is offered at most once per host lifetime (`handled`: added when the dialog opens
// or when the player's inline form answers it). A profile change clears everything.
// Navigation while the dialog is open = Ahora no, except a pending save (the PUT decides) and the
// end of a session (/profiles, /setup: no answer, no dismissal). A same-page REPLACE (only the
// search params change, e.g. a season that disappeared) is not a navigation.
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { mutationKeys } from '@/api/queryKeys';
import type { RatingKind, RatingPrompt } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { isSamePageReplace } from '@/lib/routerLocation';
import { RatingDialog } from './RatingDialog';
import { RatingPromptContext, type RatingPromptApi, type SubscribeLocation } from './ratingPrompt';
import { dismissInBackground } from './useRatingWrite';

/** Where AuthProvider sends a session that ended (401, sign-out, profile switch). */
const SESSION_END_PATHS = new Set(['/profiles', '/setup']);

const keyOf = (kind: RatingKind, id: number) => `${kind}:${id}`;

export function RatingPromptHost({ children, subscribeLocation }: { children: ReactNode; subscribeLocation?: SubscribeLocation }) {
  const queryClient = useQueryClient();
  const { session } = useAuth();
  const profileId = session?.profile_id ?? null;
  // `handled` belongs to one profile: read it through `handledKeys()` (callbacks and effects only,
  // never during render), which starts a new set once the committed profile changed.
  const profileRef = useRef(profileId);
  useLayoutEffect(() => {
    profileRef.current = profileId;
  }, [profileId]);
  const handled = useRef({ profileId, keys: new Set<string>() });
  const handledKeys = useCallback(() => {
    if (handled.current.profileId !== profileRef.current) handled.current = { profileId: profileRef.current, keys: new Set() };
    return handled.current.keys;
  }, []);
  const [pending, setPending] = useState<RatingPrompt | null>(null);
  const [shown, setShown] = useState<RatingPrompt | null>(null);
  const [suppressions, setSuppressions] = useState(0);
  const shownKey = shown ? keyOf(shown.kind, shown.id) : null;
  // `offer` (callbacks only) reads the committed shown key.
  const shownKeyRef = useRef(shownKey);
  useLayoutEffect(() => {
    shownKeyRef.current = shownKey;
  }, [shownKey]);

  // A profile change (switch, sign-out, expiry) forgets everything (`handledKeys()` drops the keys).
  const [profileSeen, setProfileSeen] = useState(profileId);
  if (profileSeen !== profileId) {
    setProfileSeen(profileId);
    setPending(null);
    setShown(null);
  }

  const offer = useCallback((prompt: RatingPrompt) => {
    const key = keyOf(prompt.kind, prompt.id);
    if (handledKeys().has(key) || shownKeyRef.current === key) return;
    setPending(prompt);
  }, [handledKeys]);

  const markHandled = useCallback((kind: RatingKind, id: number) => {
    const key = keyOf(kind, id);
    handledKeys().add(key);
    setPending((p) => (p && keyOf(p.kind, p.id) === key ? null : p));
  }, [handledKeys]);

  const suppress = useCallback(() => {
    setSuppressions((n) => n + 1);
    let released = false;
    return () => {
      if (released) return;
      released = true;
      setSuppressions((n) => n - 1);
    };
  }, []);

  // Open the pending prompt once nothing is shown and nobody suppresses it.
  useEffect(() => {
    if (!pending || shown || suppressions > 0) return;
    handledKeys().add(keyOf(pending.kind, pending.id));
    setShown(pending);
    setPending(null);
  }, [pending, shown, suppressions, handledKeys]);

  // Navigation while the dialog is shown (subscribed only then: the navigation that led to an
  // offer, e.g. leaving the player, does not count).
  useEffect(() => {
    if (!shown || !subscribeLocation) return undefined;
    const { kind, id } = shown;
    return subscribeLocation((change) => {
      if (isSamePageReplace(change)) return;
      setShown(null);
      if (SESSION_END_PATHS.has(change.pathname)) {
        // Not an answer: the server offers it again next time.
        handledKeys().delete(keyOf(kind, id));
        setPending(null);
        return;
      }
      const saving = queryClient.isMutating({ mutationKey: mutationKeys.ratingWrite(kind, id) }) > 0;
      if (!saving) dismissInBackground(kind, id);
    });
  }, [shown, subscribeLocation, queryClient, handledKeys]);

  const api = useMemo<RatingPromptApi>(() => ({ offer, markHandled, suppress }), [offer, markHandled, suppress]);

  return (
    <RatingPromptContext.Provider value={api}>
      {children}
      {shown && (
        <RatingDialog key={shownKey} mode="prompt" target={shown} initial={null} onClose={() => setShown(null)} />
      )}
    </RatingPromptContext.Provider>
  );
}
