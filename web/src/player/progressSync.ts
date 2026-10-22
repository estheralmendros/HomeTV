// Re-entry rule (prd2 §9 step 1, Rev 5): exit write-through plus a barrier.
// - The final PUT of a mount is registered here. Its 200 body is written into the cached
//   detail (`['film',id]` / `['episode',id]`) and the item's `['home']` entries, existing
//   entries only, and THEN the player-exit invalidation runs. A failed PUT only invalidates.
// - A new mount of the same item awaits that pending write (≤ 3 s) before its resume decision.
import type { QueryClient } from '@tanstack/react-query';
import { queryKeys, type PlayedItem } from '@/api/queryKeys';
import { runInvalidation } from '@/api/queryClient';
import type { EpisodeDetail, FilmDetail, HomeEpisode, HomeResponse, ItemType, Progress } from '@/api/types';

export interface ItemRef {
  type: ItemType;
  id: number;
}

export const BARRIER_TIMEOUT_MS = 3_000;

const itemKey = (item: ItemRef) => `${item.type}:${item.id}`;
const pendingFinal = new Map<string, Promise<void>>();

/** setQueryData on cached entries only (an updater returning undefined creates nothing). */
export function writeProgress(queryClient: QueryClient, item: ItemRef, progress: Progress): void {
  if (item.type === 'film') {
    queryClient.setQueryData<FilmDetail>(queryKeys.film(item.id), (old) => old && { ...old, progress });
  } else {
    queryClient.setQueryData<EpisodeDetail>(queryKeys.episode(item.id), (old) => old && { ...old, progress });
  }
  queryClient.setQueryData<HomeResponse>(queryKeys.home(), (old) => {
    if (!old) return old;
    const matches = (entry: { type: string; id: number }) => entry.type === item.type && entry.id === item.id;
    return {
      ...old,
      continue_watching: old.continue_watching.map((entry) => (matches(entry) ? { ...entry, progress } : entry)),
      next_up: old.next_up.map((entry) => (matches(entry) ? { ...entry, progress } : entry)),
    };
  });
}

/**
 * Registers a mount's final `PUT /api/progress` (§9 step 14). Resolves once the write-through
 * and the invalidation have run; never rejects.
 */
export function registerFinalReport(queryClient: QueryClient, item: PlayedItem, put: Promise<Progress>): Promise<void> {
  const key = itemKey(item);
  const settled = put
    .then(
      (progress) => writeProgress(queryClient, item, progress),
      () => undefined,
    )
    .then(() => runInvalidation(queryClient, { type: 'playerExit', item }))
    .catch(() => undefined)
    .finally(() => {
      if (pendingFinal.get(key) === settled) pendingFinal.delete(key);
    });
  pendingFinal.set(key, settled);
  return settled;
}

export function hasPendingFinal(item: ItemRef): boolean {
  return pendingFinal.has(itemKey(item));
}

export type BarrierOutcome = 'none' | 'settled' | 'timeout';

/**
 * Awaits this tab's pending final report of the item, for at most `timeoutMs`. On `'timeout'`
 * the caller waits for the item query's first fetch after mount instead (no home fallback).
 */
export function awaitFinalBarrier(item: ItemRef, timeoutMs = BARRIER_TIMEOUT_MS): Promise<BarrierOutcome> {
  const pending = pendingFinal.get(itemKey(item));
  if (!pending) return Promise.resolve('none');
  return new Promise<BarrierOutcome>((resolve) => {
    const timer = setTimeout(() => resolve('timeout'), timeoutMs);
    void pending.then(() => {
      clearTimeout(timer);
      resolve('settled');
    });
  });
}

/** The read-only home fallback of an episode (Continue Watching or Next Up; never a film). */
export function findHomeEpisode(home: HomeResponse | undefined, id: number): HomeEpisode | null {
  if (!home) return null;
  const isEpisode = (entry: { type: string; id: number }): entry is HomeEpisode => entry.type === 'episode' && entry.id === id;
  return home.continue_watching.find(isEpisode) ?? home.next_up.find(isEpisode) ?? null;
}

/** Test helper: forget pending reports. */
export function resetProgressSync(): void {
  pendingFinal.clear();
}
