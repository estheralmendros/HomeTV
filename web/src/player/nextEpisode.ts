// Next episode resolution (prd2 §9 step 10, D12): follow `next_episode_id`, skipping episodes
// whose media_files include the file that just played (multi-episode files), with at most
// 10 GETs. A null candidate, a 404 (or any failure), the cap or an abort → null.
import type { QueryClient } from '@tanstack/react-query';
import { episodeQuery } from '@/api/queries';
import type { EpisodeDetail } from '@/api/types';

export const MAX_NEXT_EPISODE_HOPS = 10;

export interface ResolveOptions {
  signal?: AbortSignal;
  maxHops?: number;
}

/** Fetches through the query cache (a fresh `['episode', id]` entry is reused). */
async function fetchEpisode(queryClient: QueryClient, id: number, signal: AbortSignal | undefined): Promise<EpisodeDetail | null> {
  const options = episodeQuery(id);
  const onAbort = () => void queryClient.cancelQueries({ queryKey: options.queryKey });
  signal?.addEventListener('abort', onAbort, { once: true });
  try {
    return await queryClient.fetchQuery(options);
  } catch {
    return null;
  } finally {
    signal?.removeEventListener('abort', onAbort);
  }
}

export async function resolveNextEpisode(
  queryClient: QueryClient,
  current: Pick<EpisodeDetail, 'next_episode_id'>,
  playedFileId: number,
  { signal, maxHops = MAX_NEXT_EPISODE_HOPS }: ResolveOptions = {},
): Promise<EpisodeDetail | null> {
  let candidate = current.next_episode_id;
  for (let hops = 0; candidate != null && hops < maxHops; hops += 1) {
    if (signal?.aborted) return null;
    const episode = await fetchEpisode(queryClient, candidate, signal);
    if (!episode || signal?.aborted) return null;
    if (!episode.media_files.some((file) => file.id === playedFileId)) return episode;
    candidate = episode.next_episode_id;
  }
  return null;
}
