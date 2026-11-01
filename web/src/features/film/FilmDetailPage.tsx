// Film detail (prd2 §7). The version the player would start (VersionPicker selection, default
// selectVersion) drives the Resume / Play labels via startPosition (D10/D11). Player URLs:
// Resume and Play → ?file=<selected> (no t); Play from start → ?file=<selected>&t=0.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, errorMessage } from '@/api/errors';
import { filmQuery } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import { runInvalidation } from '@/api/queryClient';
import type { FilmDetail } from '@/api/types';
import { setWatched } from '@/api/watch';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { CastRow } from '@/components/CastRow';
import { DetailHero } from '@/components/DetailHero';
import { ErrorState } from '@/components/ErrorState';
import { PLAY_GLYPH } from '@/components/glyphs';
import { NotAvailable } from '@/components/NotAvailable';
import { ProgressBar } from '@/components/ProgressBar';
import { SkeletonDetail } from '@/components/Skeleton';
import { useToast } from '@/components/Toast';
import { WatchedToggle } from '@/components/WatchedToggle';
import { WatchlistToggle } from '@/components/WatchlistToggle';
import { FixMatchButton } from '@/features/admin/FixMatchButton';
import { useRatingPrompt } from '@/features/ratings/ratingPrompt';
import { RatingsBlock } from '@/features/ratings/RatingsBlock';
import { useWatchlistToggle } from '@/features/watchlist/useWatchlistToggle';
import { formatDuration, formatRating, formatRuntime } from '@/lib/format';
import { parseRouteId } from '@/lib/routeId';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { withWatched } from '@/lib/watchedPatch';
import { getClientCaps } from '@/player/capabilities';
import { playerUrl } from '@/player/playerUrl';
import { selectVersion, startPosition } from '@/player/versions';
import { S } from '@/strings.es';
import styles from './FilmDetailPage.module.css';
import { VersionPicker } from './VersionPicker';

export function FilmDetailPage() {
  const id = parseRouteId(useParams().id);
  const film = useQuery({ ...filmQuery(id ?? 0), enabled: id !== null });
  useDocumentTitle(film.data ? S.common.titleWithYear(film.data.title, film.data.year) : null);

  if (id === null || (film.error instanceof ApiError && film.error.status === 404)) {
    return <NotAvailable message={S.film.notFound} backTo="/films" backLabel={S.film.backToFilms} />;
  }
  if (film.isPending) return <div className="page"><SkeletonDetail /></div>;
  // Only a failed first load replaces the page: a failed background refetch (e.g. the one after a
  // rating write with the server down) keeps the cached detail and any open dialog on screen.
  if (film.data === undefined) {
    return (
      <div className="page">
        <ErrorState message={errorMessage(film.error)} onRetry={() => void film.refetch()} />
      </div>
    );
  }
  return <FilmDetailView key={film.data.id} film={film.data} />;
}

export function filmMeta(film: FilmDetail): string {
  return [
    film.year !== null ? String(film.year) : null,
    film.runtime_minutes ? formatRuntime(film.runtime_minutes) : null,
    film.genres.length > 0 ? film.genres.map((g) => g.name).join(', ') : null,
    film.vote_average !== null ? formatRating(film.vote_average) : null,
  ]
    .filter((part): part is string => part !== null)
    .join(' · ');
}

function FilmDetailView({ film }: { film: FilmDetail }) {
  const isAdmin = useIsAdmin();
  const navigate = useNavigate();
  return (
    <div className="page">
      <DetailHero
        title={film.title}
        poster={film.poster}
        backdrop={film.backdrop}
        meta={filmMeta(film)}
        extra={<FilmRatings film={film} />}
        tagline={film.tagline}
        overview={film.overview}
      >
        <FilmActions film={film} />
        {isAdmin && (
          <FixMatchButton
            mode={{ kind: 'film', id: film.id, title: film.title, year: film.year }}
            onMatched={(target) => {
              if (target.id !== film.id) navigate(`/films/${target.id}`, { replace: true });
            }}
          />
        )}
      </DetailHero>
      <CastRow cast={film.cast} />
    </div>
  );
}

function FilmRatings({ film }: { film: FilmDetail }) {
  return (
    <RatingsBlock
      kind="film"
      id={film.id}
      title={film.title}
      poster={film.poster}
      myRating={film.my_rating}
      ratings={film.ratings}
      average={film.household_average}
    />
  );
}

function FilmActions({ film }: { film: FilmDetail }) {
  const caps = getClientCaps();
  const files = film.media_files;
  const [chosenId, setChosenId] = useState<number | null>(null);
  const defaultFile = useMemo(() => selectVersion(files, caps, film.progress), [files, caps, film.progress]);
  const selected = files.find((f) => f.id === chosenId) ?? defaultFile;
  const p = film.progress;

  return (
    <>
      {selected ? <PlayButtons film={film} fileId={selected.id} /> : <NoPlayableFile />}
      <div className={styles.secondary}>
        <FilmWatchedToggle film={film} />
        <FilmWatchlistToggle film={film} />
        {p && !p.watched && p.position_seconds > 0 && (
          <span className={styles.progress}>
            <ProgressBar value={p.position_seconds} max={p.duration_seconds} />
          </span>
        )}
      </div>
      {selected && <VersionPicker files={files} selectedId={selected.id} caps={caps} onChange={setChosenId} />}
    </>
  );
}

function PlayButtons({ film, fileId }: { film: FilmDetail; fileId: number }) {
  const file = film.media_files.find((f) => f.id === fileId)!;
  const start = startPosition(file, film.media_files, film.progress);
  const part = file.part_number;
  if (start.seconds > 0) {
    const time = formatDuration(start.seconds, { withHours: true });
    return (
      <>
        <Link to={playerUrl('film', film.id, { file: fileId })} className="button button-primary">
          <span aria-hidden="true">{PLAY_GLYPH}</span>
          {part !== null ? S.film.resumePart(part, time) : S.film.resume(time)}
        </Link>
        <Link to={playerUrl('film', film.id, { file: fileId, t: 0 })} className="button">
          {S.film.playFromStart}
        </Link>
      </>
    );
  }
  return (
    <Link to={playerUrl('film', film.id, { file: fileId })} className="button button-primary">
      <span aria-hidden="true">{PLAY_GLYPH}</span>
      {part !== null ? S.film.playPart(part) : S.film.play}
    </Link>
  );
}

function NoPlayableFile() {
  return (
    <>
      <button type="button" className="button button-primary" disabled>
        <span aria-hidden="true">{PLAY_GLYPH}</span>
        {S.film.play}
      </button>
      <p className={styles.hint}>{S.film.noPlayableFile}</p>
    </>
  );
}

function FilmWatchlistToggle({ film }: { film: FilmDetail }) {
  const watchlist = useWatchlistToggle('film', film.id, film.in_watchlist);
  return <WatchlistToggle inList={watchlist.inList} disabled={watchlist.pending} onToggle={watchlist.toggle} />;
}

/**
 * Optimistic: patch progress.watched in ['film', id], roll back + toast on error, invalidate on
 * settle. A `rating_prompt` in the answer is offered to the prompt host (ratings prd2 FR-W9.3).
 */
function FilmWatchedToggle({ film }: { film: FilmDetail }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { offer } = useRatingPrompt();
  const key = queryKeys.film(film.id);
  const mutation = useMutation({
    mutationFn: (watched: boolean) => setWatched({ target: 'film', id: film.id, watched }),
    onMutate: async (watched) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<FilmDetail>(key);
      queryClient.setQueryData<FilmDetail>(key, (old) => old && { ...old, progress: withWatched(old.progress, watched) });
      return { previous };
    },
    onSuccess: (response) => {
      if (response.rating_prompt) offer(response.rating_prompt);
    },
    onError: (err, _watched, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      toast.show(errorMessage(err));
    },
    onSettled: () => runInvalidation(queryClient, { type: 'watchedFilm', id: film.id }),
  });
  return (
    <WatchedToggle
      watched={film.progress?.watched === true}
      disabled={mutation.isPending}
      onToggle={(watched) => mutation.mutate(watched)}
    />
  );
}
