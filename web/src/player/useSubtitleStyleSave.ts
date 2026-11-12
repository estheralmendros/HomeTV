// Saving the subtitle style when the "Formato de los subtítulos" panel closes with a change.
// Owned by PlayerPage (it outlives the panel): ['me'] is updated at once (a sign-in refetch in flight
// is cancelled, so it cannot revert it), then one `PUT /api/me/subtitle-style`; a newer save while
// one is in flight waits for it and then sends the newest style (latest wins, module-wide). Each
// wish records the session profile; it is sent only while the profile getter still answers it, and
// a profile change (trackChoiceSession.setActiveProfile) drops the queued one, so a look is never
// sent with another profile's token. A
// failure is logged and toasted by the ratings "form gone" rule: the player's `notify` while the
// page is mounted (layout-effect ref), else the app toast; the style stays applied for the session.
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useLayoutEffect, useRef } from 'react';
import { isApiError, logApiError } from '@/api/errors';
import { putSubtitleStyle } from '@/api/profiles';
import { queryKeys } from '@/api/queryKeys';
import type { Me, SubtitleStyle } from '@/api/types';
import { useToast } from '@/components/Toast';
import { S } from '@/strings.es';
import { currentProfileId, onActiveProfileChange } from './trackChoiceSession';

interface Wish {
  profileId: number | null;
  style: SubtitleStyle;
  onFailure: () => void;
}

let inFlight = false;
let queued: Wish | null = null;
let idleWaiters: Array<() => void> = [];

function notifyIdle(): void {
  const waiters = idleWaiters;
  idleWaiters = [];
  for (const resolve of waiters) resolve();
}

onActiveProfileChange(() => {
  queued = null;
});

function send(wish: Wish): void {
  if (wish.profileId !== currentProfileId()) {
    notifyIdle();
    return;
  }
  inFlight = true;
  putSubtitleStyle(wish.style)
    .catch((err: unknown) => {
      if (isApiError(err)) logApiError(err);
      else console.warn('[player] subtitle style not saved', err);
      wish.onFailure();
    })
    .finally(() => {
      inFlight = false;
      const next = queued;
      queued = null;
      if (next) send(next);
      else notifyIdle();
    });
}

function enqueue(wish: Wish): void {
  if (inFlight) queued = wish;
  else send(wish);
}

/** Tests: resolves once no save is in flight nor queued. */
export function subtitleStyleSaveIdle(): Promise<void> {
  if (!inFlight) return Promise.resolve();
  return new Promise((resolve) => idleWaiters.push(resolve));
}

/** Tests: forget a queued save (tests/setup.ts). */
export function resetSubtitleStyleSave(): void {
  inFlight = false;
  queued = null;
  notifyIdle();
}

export function useSubtitleStyleSave(notify: (text: string) => void): (style: SubtitleStyle) => void {
  const queryClient = useQueryClient();
  const toast = useToast();
  const mounted = useRef(false);
  useLayoutEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const latest = useRef({ notify, appToast: toast.show });
  latest.current = { notify, appToast: toast.show };

  return useCallback(
    (style: SubtitleStyle) => {
      const key = queryKeys.me();
      const apply = () => queryClient.setQueryData<Me>(key, (me) => me && { ...me, subtitle_style: style });
      // A refetch in flight (sign-in) is cancelled; a manual write is what its revert restores.
      apply();
      void queryClient.cancelQueries({ queryKey: key });
      enqueue({
        profileId: currentProfileId(),
        style,
        onFailure: () => {
          const { notify: say, appToast } = latest.current;
          if (mounted.current) say(S.player.subtitleStyle.saveFailed);
          else appToast(S.player.subtitleStyle.saveFailed);
        },
      });
    },
    [queryClient],
  );
}
