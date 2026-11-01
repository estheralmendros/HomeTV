// "Mi lista" toggle on film/show detail (watchlist prd2 FR-W6): optimistic patch of the detail's
// `in_watchlist`, rollback + toast on error, invalidation on settle (the server's truth wins).
// No success toast: the button change is the confirmation.
import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import { mutationKeys, queryKeys } from '@/api/queryKeys';
import type { FilmDetail, ShowDetail, WatchlistKind } from '@/api/types';
import { addToWatchlist, removeFromWatchlist } from '@/api/watchlist';
import { useToast } from '@/components/Toast';

export interface WatchlistToggleState {
  inList: boolean;
  toggle: (nextInList: boolean) => void;
  /**
   * A write for this title is in flight (from this or an earlier mount of the page): the button stays
   * disabled meanwhile, so two writes never race and a late rollback never lands over a newer value.
   */
  pending: boolean;
}

export function useWatchlistToggle(kind: WatchlistKind, id: number, inList: boolean): WatchlistToggleState {
  const queryClient = useQueryClient();
  const toast = useToast();
  const key = queryKeys.title(kind, id);
  const mutationKey = mutationKeys.watchlistWrite(kind, id);
  const pending = useIsMutating({ mutationKey }) > 0;
  const mutation = useMutation({
    mutationKey,
    // 'always' (not the default 'online'): offline, the write fails at once and rolls back with the
    // connection toast, instead of pausing with the optimistic state shown and sending it later.
    networkMode: 'always',
    mutationFn: (next: boolean) => (next ? addToWatchlist(kind, id) : removeFromWatchlist(kind, id)),
    onMutate: async (next) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<FilmDetail | ShowDetail>(key);
      queryClient.setQueryData<FilmDetail | ShowDetail>(key, (old) => old && { ...old, in_watchlist: next });
      return { previous };
    },
    onError: (err, _next, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      toast.show(errorMessage(err));
    },
    onSettled: () => runInvalidation(queryClient, { type: 'watchlistChanged', kind, id }),
  });
  return { inList, toggle: (next) => mutation.mutate(next), pending };
}
