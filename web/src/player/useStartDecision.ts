// The player's start decision (prd2 §9 steps 1–2, Rev 4/5/7): which file and from where, taken
// ONCE per mount.
// - Re-entry barrier: a pending final report of this item (this tab's previous visit) is awaited
//   for ≤ 3 s; on timeout the decision waits for the item query's first fetch after this mount
//   and the home fallback is not used.
// - Source: the candidate with the newest dataUpdatedAt among the item query and, for episodes,
//   the read-only home entry (Continue Watching, in progress or Next Up), so a home start never waits for
//   GET /api/episodes/{id}.
// - File: `?file=` when it belongs to the item, else selectVersion(). Start: `?t=` (one-shot),
//   else startPosition() (D10/D11) with its "different version" flag.
import { useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { queryKeys, type PlayedItem } from '@/api/queryKeys';
import type { EpisodeDetail, FilmDetail, HomeEpisode, HomeResponse, ItemType, MediaFile, Progress } from '@/api/types';
import { getClientCaps } from './capabilities';
import { awaitFinalBarrier, findHomeEpisode, hasPendingFinal, type BarrierOutcome } from './progressSync';
import { selectVersion, startPosition } from './versions';

export type PlayerItem = FilmDetail | EpisodeDetail;
export type DecisionSource = PlayerItem | HomeEpisode;

export interface StartDecision {
  file: MediaFile;
  files: MediaFile[];
  progress: Progress | null;
  start: number;
  /** D11: resumable progress exists but this version doesn't match it (never with `?t=`). */
  differentVersion: boolean;
  source: DecisionSource;
}

export type StartDecisionResult = StartDecision | 'no_file' | null;

export interface StartDecisionInput {
  type: ItemType;
  id: number;
  query: UseQueryResult<PlayerItem>;
  /** `?file=` read at mount. */
  fileParam: number | null;
  /** `?t=` read at mount (one-shot). */
  tParam: number | null;
}

/** The progress-report item of a decision source (episodes carry their show/season). */
export function playedItemOf(type: ItemType, id: number, source: DecisionSource | undefined): PlayedItem {
  if (type === 'film') return { type: 'film', id };
  const episode = source as EpisodeDetail | HomeEpisode | undefined;
  return { type: 'episode', id, showId: episode?.show_id ?? 0, seasonNumber: episode?.season_number ?? 0 };
}

export function decide(source: DecisionSource, fileParam: number | null, tParam: number | null): StartDecisionResult {
  const files = source.media_files;
  const progress = source.progress;
  const file = files.find((f) => f.id === fileParam) ?? selectVersion(files, getClientCaps(), progress);
  if (!file) return 'no_file';
  if (tParam !== null) return { file, files, progress, start: tParam, differentVersion: false, source };
  const { seconds, differentVersion } = startPosition(file, files, progress);
  return { file, files, progress, start: seconds, differentVersion, source };
}

export function useStartDecision({ type, id, query, fileParam, tParam }: StartDecisionInput): StartDecisionResult {
  const queryClient = useQueryClient();
  const [barrier, setBarrier] = useState<BarrierOutcome | 'pending'>(() => (hasPendingFinal({ type, id }) ? 'pending' : 'none'));
  const [result, setResult] = useState<StartDecisionResult>(null);

  useEffect(() => {
    if (barrier !== 'pending') return;
    let alive = true;
    void awaitFinalBarrier({ type, id }).then((outcome) => {
      if (alive) setBarrier(outcome);
    });
    return () => {
      alive = false;
    };
  }, [barrier, type, id]);

  const { data, isFetchedAfterMount, dataUpdatedAt } = query;
  useEffect(() => {
    if (result !== null || barrier === 'pending') return;
    const candidates: Array<{ source: DecisionSource; at: number }> = [];
    if (data && (barrier !== 'timeout' || isFetchedAfterMount)) candidates.push({ source: data, at: dataUpdatedAt });
    if (type === 'episode' && barrier !== 'timeout') {
      const homeKey = queryKeys.home();
      const home = findHomeEpisode(queryClient.getQueryData<HomeResponse>(homeKey), id);
      if (home) candidates.push({ source: home, at: queryClient.getQueryState(homeKey)?.dataUpdatedAt ?? 0 });
    }
    const newest = candidates.reduce<(typeof candidates)[number] | null>((best, c) => (best && best.at >= c.at ? best : c), null);
    if (newest) setResult(decide(newest.source, fileParam, tParam));
  }, [result, barrier, data, dataUpdatedAt, isFetchedAfterMount, type, id, fileParam, tParam, queryClient]);

  return result;
}
