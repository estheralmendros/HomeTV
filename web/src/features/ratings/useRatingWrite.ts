// Rating writes (ratings prd2 FR-W7). Save and remove patch the cached detail optimistically and
// run `ratingChanged` on settle. Toasts and rollback live in the useMutation options callbacks,
// which run even after the form unmounted ("form gone" rule): while the caller is mounted a save
// reports through `notify` (default: the app toast; the player passes its own toasts), once it is
// gone through the app toast, for both outcomes. A removal always closes its form at once, so its
// outcome always goes to the app toast (success: none).
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useRef } from 'react';
import { errorMessage, isApiError, logApiError } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import { mutationKeys, queryKeys } from '@/api/queryKeys';
import { dismissRatingPrompt, removeRating, setRating } from '@/api/ratings';
import type { FilmDetail, RatingKind, ShowDetail } from '@/api/types';
import { useToast } from '@/components/Toast';
import { S } from '@/strings.es';

export interface SaveCallbacks {
  /** The save succeeded while the caller is mounted. */
  onSuccess?: () => void;
  /** The save failed while the caller is mounted. */
  onError?: (err: unknown) => void;
}

export interface RatingWrite {
  save: (stars: number, callbacks?: SaveCallbacks) => void;
  remove: () => void;
  /** A save of this hook is in flight. */
  saving: boolean;
}

const isNotFound = (err: unknown) => isApiError(err) && err.status === 404;

/** "Ahora no": fire-and-forget; a failure is only logged (the server will offer again). */
export function dismissInBackground(kind: RatingKind, id: number): void {
  void dismissRatingPrompt(kind, id).catch((err: unknown) => {
    if (isApiError(err)) logApiError(err);
  });
}

export function useRatingWrite(kind: RatingKind, id: number, options: { notify?: (text: string) => void } = {}): RatingWrite {
  const queryClient = useQueryClient();
  const toast = useToast();
  const key = queryKeys.title(kind, id);
  const mutationKey = mutationKeys.ratingWrite(kind, id);

  // A layout effect: its cleanup runs in the commit that removes the form, before React flushes the
  // deleted tree's passive effects, so a save settling in between never reaches a dead `notify`.
  const mounted = useRef(false);
  useLayoutEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const notifyRef = useRef(options.notify);
  notifyRef.current = options.notify;
  const appToast = toast.show;
  /** `notify` while mounted, the app toast once the form is gone. */
  const say = (text: string) => {
    const notify = notifyRef.current;
    if (mounted.current && notify) notify(text);
    else appToast(text);
  };

  const patch = async (myRating: number | null) => {
    await queryClient.cancelQueries({ queryKey: key });
    const previous = queryClient.getQueryData<FilmDetail | ShowDetail>(key);
    queryClient.setQueryData<FilmDetail | ShowDetail>(key, (old) => old && { ...old, my_rating: myRating });
    return { previous };
  };
  const restore = (context: { previous: FilmDetail | ShowDetail | undefined } | undefined) => {
    if (context?.previous) queryClient.setQueryData(key, context.previous);
  };
  // Fire-and-forget: TanStack Query awaits a promise returned by `onSettled` before the per-call
  // callbacks run, so returning the refetch would hold the form's close / error line until every
  // matching query refetched.
  const settle = () => {
    void runInvalidation(queryClient, { type: 'ratingChanged', kind, id });
  };

  const saveMutation = useMutation({
    mutationKey,
    // 'always': offline, the save fails at once (error line + Reintentar) instead of pausing.
    networkMode: 'always',
    mutationFn: (stars: number) => setRating(kind, id, stars),
    onMutate: (stars) => patch(stars),
    onSuccess: () => say(S.ratings.saved),
    onError: (err, _stars, context) => {
      restore(context);
      if (isNotFound(err)) say(errorMessage(err));
      // While mounted the form shows the error line instead of a toast.
      else if (!mounted.current) appToast(S.ratings.saveError);
    },
    onSettled: settle,
  });

  const removeMutation = useMutation({
    mutationKey,
    networkMode: 'always',
    mutationFn: () => removeRating(kind, id),
    onMutate: () => patch(null),
    onError: (err, _vars, context) => {
      restore(context);
      appToast(isNotFound(err) ? errorMessage(err) : S.ratings.updateError);
    },
    onSettled: settle,
  });

  return {
    save: (stars, callbacks = {}) => saveMutation.mutate(stars, { onSuccess: callbacks.onSuccess, onError: callbacks.onError }),
    remove: () => removeMutation.mutate(),
    saving: saveMutation.isPending,
  };
}
