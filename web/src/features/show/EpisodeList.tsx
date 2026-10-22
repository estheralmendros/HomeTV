// Episode rows of a season (prd2 §8 step 4): still, "E3 · Título", air date, runtime, clamped
// overview, progress ("Quedan 9 min") or ✓, play and an optimistic watched toggle. Rows never
// list versions: the player runs selectVersion on the episode's media_files.
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '@/api/errors';
import { queryKeys } from '@/api/queryKeys';
import { runInvalidation } from '@/api/queryClient';
import type { EpisodeSummary, SeasonDetail } from '@/api/types';
import { setWatched } from '@/api/watch';
import { Artwork } from '@/components/Artwork';
import { ProgressBar } from '@/components/ProgressBar';
import { useToast } from '@/components/Toast';
import { WatchedToggle } from '@/components/WatchedToggle';
import { cx } from '@/lib/cx';
import { episodeTitle } from '@/lib/episodes';
import { episodeShort, formatDate, formatRuntime } from '@/lib/format';
import { withWatched } from '@/lib/watchedPatch';
import { playerUrl } from '@/player/playerUrl';
import { S } from '@/strings.es';
import styles from './ShowDetailPage.module.css';

export function EpisodeList({ showId, seasonNumber, episodes }: { showId: number; seasonNumber: number; episodes: EpisodeSummary[] }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const key = queryKeys.season(showId, seasonNumber);
  const patch = (episodeId: number, watched: boolean) =>
    queryClient.setQueryData<SeasonDetail>(key, (old) =>
      old && {
        ...old,
        episodes: old.episodes.map((e) => (e.id === episodeId ? { ...e, progress: withWatched(e.progress, watched) } : e)),
      },
    );
  const mutation = useMutation({
    mutationFn: ({ id, watched }: { id: number; watched: boolean }) => setWatched({ target: 'episode', id, watched }),
    onMutate: async ({ id, watched }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<SeasonDetail>(key);
      patch(id, watched);
      return { previous };
    },
    onError: (err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      toast.show(errorMessage(err));
    },
    onSettled: () => runInvalidation(queryClient, { type: 'watchedEpisodes', showId }),
  });

  return (
    <ul className={styles.episodes}>
      {episodes.map((episode) => (
        <EpisodeRow
          key={episode.id}
          episode={episode}
          pending={mutation.isPending && mutation.variables?.id === episode.id}
          onToggleWatched={(watched) => mutation.mutate({ id: episode.id, watched })}
        />
      ))}
    </ul>
  );
}

function runtimeMinutes(episode: EpisodeSummary): number | null {
  if (episode.runtime_minutes) return episode.runtime_minutes;
  return episode.duration_seconds ? Math.round(episode.duration_seconds / 60) : null;
}

function EpisodeRow({
  episode,
  pending,
  onToggleWatched,
}: {
  episode: EpisodeSummary;
  pending: boolean;
  onToggleWatched: (watched: boolean) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const to = playerUrl('episode', episode.id);
  const code = episodeShort(episode.episode_number);
  const runtime = runtimeMinutes(episode);
  const meta = [episode.air_date ? formatDate(episode.air_date) : null, runtime ? formatRuntime(runtime) : null]
    .filter((part): part is string => part !== null)
    .join(' · ');
  const p = episode.progress;
  const inProgress = p !== null && !p.watched && p.position_seconds > 0;
  const duration = p?.duration_seconds || episode.duration_seconds || 0;

  return (
    <li className={styles.episode}>
      <Link to={to} className={styles.still} tabIndex={-1} aria-hidden="true">
        <Artwork image={episode.still} title={episodeTitle(episode)} kind="still" />
      </Link>
      <div className={styles.episodeBody}>
        <h3 className={styles.episodeTitle}>
          <Link to={to}>{`${code} · ${episodeTitle(episode)}`}</Link>
        </h3>
        {meta && <p className={styles.episodeMeta}>{meta}</p>}
        {episode.overview && (
          <button
            type="button"
            className={cx(styles.overview, expanded && styles.overviewExpanded)}
            aria-expanded={expanded}
            onClick={() => setExpanded((v) => !v)}
          >
            {episode.overview}
          </button>
        )}
        {inProgress && (
          <div className={styles.episodeProgress}>
            <ProgressBar value={p.position_seconds} max={duration} />
            <span>{S.show.remaining(Math.max(1, Math.round((duration - p.position_seconds) / 60)))}</span>
          </div>
        )}
      </div>
      <div className={styles.episodeActions}>
        <Link to={to} className={cx('button', styles.play)} aria-label={S.show.playEpisode(code)}>
          ▶
        </Link>
        <WatchedToggle compact watched={p?.watched === true} disabled={pending} onToggle={onToggleWatched} />
      </div>
    </li>
  );
}
