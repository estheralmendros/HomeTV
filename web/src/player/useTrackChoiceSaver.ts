// Saving a manual audio/subtitle pick (prd2.md FR-W6): merge the part into the held choice, record
// the session override, patch every cached copy of the title's choice (keeping each entry's update
// time, so the start's newest-source rule is unchanged), then one serialized PUT per title
// (trackChoiceSession.enqueueWrite). On 'ok': the trackChoiceChanged invalidation (active keys
// refetched) and only then the settle that clears the override; on 'failed': the override stays for
// the tab, the error is logged (no toast). The player never awaits any of it; leaving it cancels
// nothing.
import { useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { useCallback, useRef } from 'react';
import { isApiError, logApiError } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import { queryKeys } from '@/api/queryKeys';
import { putTrackChoice } from '@/api/trackChoices';
import type { AudioChoice, EpisodeDetail, FilmDetail, HomeResponse, SubtitleChoice, TitleKind, TrackChoice } from '@/api/types';
import { withAudio, withSubtitles } from './trackChoice';
import { enqueueWrite, pendingChoice, rememberPick, settlePick } from './trackChoiceSession';

export interface ChoiceSource {
  track_choice: TrackChoice | null;
}

function writeKeepingFreshness<T>(qc: QueryClient, key: QueryKey, data: T): void {
  const state = qc.getQueryState(key);
  qc.setQueryData(key, data, { updatedAt: state?.dataUpdatedAt });
  // setQueryData clears `isInvalidated`; an entry that was invalidated stays so (no refetch here).
  if (state?.isInvalidated) void qc.invalidateQueries({ queryKey: key, exact: true, refetchType: 'none' });
}

/**
 * Writes `next` into every cached entry that holds the title's choice, and only those: the film
 * detail; for a show each cached episode detail of that show and Home when one of its episodes
 * belongs to it. Never changes an entry's `dataUpdatedAt` nor writes another title's entry.
 */
export function patchTrackChoice(qc: QueryClient, kind: TitleKind, id: number, next: TrackChoice): void {
  if (kind === 'film') {
    const key = queryKeys.film(id);
    const film = qc.getQueryData<FilmDetail>(key);
    if (film) writeKeepingFreshness(qc, key, { ...film, track_choice: next });
    return;
  }
  for (const query of qc.getQueryCache().findAll({ queryKey: ['episode'] })) {
    const episode = query.state.data as EpisodeDetail | undefined;
    if (episode?.show_id === id) writeKeepingFreshness(qc, query.queryKey, { ...episode, track_choice: next });
  }
  const homeKey = queryKeys.home();
  const home = qc.getQueryData<HomeResponse>(homeKey);
  const holds = (item: HomeResponse['continue_watching'][number]) => item.type === 'episode' && item.show_id === id;
  if (home?.continue_watching.some(holds)) {
    writeKeepingFreshness(qc, homeKey, {
      ...home,
      continue_watching: home.continue_watching.map((item) => (holds(item) ? { ...item, track_choice: next } : item)),
    });
  }
}

export interface TrackChoiceSaverInput {
  /** The title (film, or the episode's show); null until known. */
  kind: TitleKind;
  id: number | null;
  /** This tab's session profile (the queue re-checks it at send time). */
  profileId: number | null;
  /** PlayerPage's live source (`query.data ?? ready.source`), read at pick time. */
  source: ChoiceSource | null | undefined;
}

export interface TrackChoiceSaver {
  /** The live held choice: the session override, else the live source's `track_choice`. */
  base: () => TrackChoice | null;
  saveAudio: (part: AudioChoice) => void;
  saveSubtitles: (part: SubtitleChoice) => void;
}

export function useTrackChoiceSaver(input: TrackChoiceSaverInput): TrackChoiceSaver {
  const queryClient = useQueryClient();
  // Updated on every render: the handlers never close over an old source.
  const live = useRef(input);
  live.current = input;

  const base = useCallback((): TrackChoice | null => {
    const { kind, id, profileId, source } = live.current;
    const pending = profileId !== null && id !== null ? pendingChoice(profileId, kind, id) : undefined;
    return pending ?? source?.track_choice ?? null;
  }, []);

  const save = useCallback(
    (build: (held: TrackChoice | null) => TrackChoice) => {
      const { kind, id, profileId } = live.current;
      if (profileId === null || id === null) return;
      const next = build(base());
      const seq = rememberPick(profileId, kind, id, next);
      patchTrackChoice(queryClient, kind, id, next);
      const event = { type: 'trackChoiceChanged', kind, id } as const;
      void enqueueWrite(profileId, kind, id, seq, () => putTrackChoice(kind, id, next)).then(async ({ outcome, error }) => {
        if (outcome === 'ok') {
          await runInvalidation(queryClient, event).catch(() => undefined);
          settlePick(profileId, kind, id, seq, true);
        } else if (outcome === 'failed') {
          settlePick(profileId, kind, id, seq, false);
          if (isApiError(error)) logApiError(error);
          else console.warn('[player] track choice not saved', error);
          void runInvalidation(queryClient, event);
        }
      });
    },
    [base, queryClient],
  );

  const saveAudio = useCallback((part: AudioChoice) => save((held) => withAudio(held, part)), [save]);
  const saveSubtitles = useCallback((part: SubtitleChoice) => save((held) => withSubtitles(held, part)), [save]);
  return { base, saveAudio, saveSubtitles };
}
