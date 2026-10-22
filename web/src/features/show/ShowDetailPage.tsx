// Show detail (prd2 §8): header, next-episode button, season selector synced to ?season=N
// (default: next_episode's season, else the first), the season's episodes and bulk watched
// actions. A 404 on the season (its episodes went missing) refetches the show and falls back
// to the default season.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError, errorMessage } from '@/api/errors';
import { seasonQuery, showQuery } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import { runInvalidation } from '@/api/queryClient';
import type { ShowDetail, ShowSeason } from '@/api/types';
import { setWatched } from '@/api/watch';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { CastRow } from '@/components/CastRow';
import { DetailHero } from '@/components/DetailHero';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { NotAvailable } from '@/components/NotAvailable';
import { SkeletonDetail, SkeletonRow } from '@/components/Skeleton';
import { useToast } from '@/components/Toast';
import { FixMatchButton } from '@/features/admin/FixMatchButton';
import { episodeCode, seasonLabel } from '@/lib/format';
import { parseRouteId } from '@/lib/routeId';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { playerUrl } from '@/player/playerUrl';
import { S } from '@/strings.es';
import { EpisodeList } from './EpisodeList';
import { SeasonSelector } from './SeasonSelector';
import styles from './ShowDetailPage.module.css';

export function ShowDetailPage() {
  const id = parseRouteId(useParams().id);
  const show = useQuery({ ...showQuery(id ?? 0), enabled: id !== null });
  useDocumentTitle(show.data ? S.common.titleWithYear(show.data.title, show.data.year) : null);

  if (id === null || (show.error instanceof ApiError && show.error.status === 404)) {
    return <NotAvailable message={S.show.notFound} backTo="/shows" backLabel={S.show.backToShows} />;
  }
  if (show.isPending) {
    return (
      <div className="page">
        <SkeletonDetail />
      </div>
    );
  }
  if (show.isError) {
    return (
      <div className="page">
        <ErrorState message={errorMessage(show.error)} onRetry={() => void show.refetch()} />
      </div>
    );
  }
  return <ShowDetailView key={show.data.id} show={show.data} />;
}

/** "2005 · Finalizada · 9 temporadas · 201 episodios" (specials not counted; unknown status hidden). */
export function showMeta(show: ShowDetail): string {
  const regular = show.seasons.filter((s) => s.season_number > 0);
  const episodes = regular.reduce((sum, s) => sum + s.episode_count, 0);
  return [
    show.year !== null ? String(show.year) : null,
    show.status ? (S.show.status[show.status] ?? null) : null,
    regular.length > 0 ? S.common.seasons(regular.length) : null,
    regular.length > 0 ? S.common.episodes(episodes) : null,
  ]
    .filter((part): part is string => part !== null)
    .join(' · ');
}

function parseSeasonParam(raw: string | null): number | null {
  return raw !== null && /^\d+$/.test(raw) ? Number(raw) : null;
}

function defaultSeason(show: ShowDetail): number | null {
  const next = show.next_episode?.season_number;
  if (next !== undefined && show.seasons.some((s) => s.season_number === next)) return next;
  return show.seasons[0]?.season_number ?? null;
}

function ShowDetailView({ show }: { show: ShowDetail }) {
  const isAdmin = useIsAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const panelId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = parseSeasonParam(searchParams.get('season'));
  const selected = show.seasons.find((s) => s.season_number === requested) ?? show.seasons.find((s) => s.season_number === defaultSeason(show));

  const selectSeason = (n: number) =>
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('season', String(n));
        return next;
      },
      { replace: true },
    );

  const onSeasonGone = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.show(show.id) });
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('season');
        return next;
      },
      { replace: true },
    );
  };

  return (
    <div className="page">
      <DetailHero
        title={show.title}
        poster={show.poster}
        backdrop={show.backdrop}
        meta={showMeta(show)}
        extra={show.unwatched_episode_count > 0 && <p className={styles.unwatched}>{S.common.unwatched(show.unwatched_episode_count)}</p>}
        overview={show.overview}
      >
        <NextEpisodeButton show={show} />
        <MarkWatchedButton
          showId={show.id}
          target="show"
          id={show.id}
          watched={show.unwatched_episode_count > 0}
          label={show.unwatched_episode_count > 0 ? S.show.markShowWatched : S.show.markShowUnwatched}
        />
        {isAdmin && (
          <FixMatchButton
            mode={{ kind: 'show', id: show.id, title: show.title, year: show.year }}
            onMatched={(target) => {
              if (target.id !== show.id) navigate(`/shows/${target.id}`, { replace: true });
            }}
          />
        )}
      </DetailHero>

      {selected ? (
        <>
          <SeasonSelector seasons={show.seasons} selected={selected.season_number} onSelect={selectSeason} panelId={panelId} />
          <div id={panelId} role="tabpanel" aria-labelledby={`${panelId}-tab-${selected.season_number}`}>
            <SeasonSection key={selected.season_number} show={show} season={selected} onGone={onSeasonGone} />
          </div>
        </>
      ) : (
        <EmptyState message={S.show.noEpisodes} />
      )}

      <CastRow cast={show.cast} />
    </div>
  );
}

function NextEpisodeButton({ show }: { show: ShowDetail }) {
  const next = show.next_episode;
  if (!next) return null;
  const code = episodeCode(next.season_number, next.episode_number);
  const p = next.progress;
  const inProgress = p !== null && !p.watched && p.position_seconds > 0;
  return (
    <Link to={playerUrl('episode', next.id)} className="button button-primary">
      <span aria-hidden="true">▶</span>
      {inProgress ? S.show.resume(code) : S.show.play(code)}
    </Link>
  );
}

function SeasonSection({ show, season, onGone }: { show: ShowDetail; season: ShowSeason; onGone: () => void }) {
  const detail = useQuery(seasonQuery(show.id, season.season_number));
  const onGoneRef = useRef(onGone);
  onGoneRef.current = onGone;
  const gone = detail.error instanceof ApiError && detail.error.status === 404;
  useEffect(() => {
    if (gone) onGoneRef.current();
  }, [gone]);

  const summary = [
    seasonLabel(season.season_number, season.name),
    S.common.episodes(season.episode_count),
    season.unwatched_episode_count > 0 ? S.common.unwatched(season.unwatched_episode_count) : null,
  ]
    .filter((part): part is string => part !== null)
    .join(' · ');
  const allWatched = season.unwatched_episode_count === 0;

  return (
    <section className={styles.season} aria-label={seasonLabel(season.season_number, season.name)}>
      <div className={styles.seasonHeader}>
        <h2 className={styles.seasonTitle}>{summary}</h2>
        <MarkWatchedButton
          showId={show.id}
          target="season"
          id={season.id}
          watched={!allWatched}
          label={allWatched ? S.show.markSeasonUnwatched : S.show.markSeasonWatched}
        />
      </div>
      {detail.data?.overview && <p className={styles.seasonOverview}>{detail.data.overview}</p>}
      {detail.isPending ? (
        <SkeletonRow count={4} variant="still" />
      ) : detail.isError ? (
        <ErrorState message={errorMessage(detail.error)} onRetry={() => void detail.refetch()} />
      ) : detail.data.episodes.length === 0 ? (
        <EmptyState message={S.show.noEpisodes} />
      ) : (
        <EpisodeList showId={show.id} seasonNumber={season.season_number} episodes={detail.data.episodes} />
      )}
    </section>
  );
}

/** "Marcar temporada/serie como (no) vista" → POST /api/watched; toast with `updated`. */
function MarkWatchedButton({
  showId,
  target,
  id,
  watched,
  label,
}: {
  showId: number;
  target: 'season' | 'show';
  id: number;
  watched: boolean;
  label: string;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => setWatched({ target, id, watched }),
    onSuccess: (result) => toast.show(S.show.markedToast(result.updated, watched)),
    onError: (err) => toast.show(errorMessage(err)),
    onSettled: () => runInvalidation(queryClient, { type: 'watchedEpisodes', showId }),
  });
  return (
    <button type="button" className="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
      {label}
    </button>
  );
}
