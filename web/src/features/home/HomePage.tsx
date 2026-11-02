// Home: Continue Watching and Recently Added rows from GET /api/home.
// Continue Watching holds in-progress items and each started show's Next Up episode
// (no progress → no bar). Its cards open the player WITHOUT ?file=: the player
// runs selectVersion on the home payload (multi-part and version-match rules), never a stale version choice.
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { homeQuery } from '@/api/queries';
import { errorMessage } from '@/api/errors';
import type { FilmSummary, HomeEpisode, HomeResponse } from '@/api/types';
import { EmptyLibrary } from '@/components/EmptyLibrary';
import { EmptyState } from '@/components/EmptyState';
import { EpisodeCard } from '@/components/EpisodeCard';
import { ErrorState } from '@/components/ErrorState';
import { PosterCard } from '@/components/PosterCard';
import { Row } from '@/components/Row';
import { SkeletonRow } from '@/components/Skeleton';
import { SummaryCard } from '@/components/SummaryCard';
import { cx } from '@/lib/cx';
import { episodeLabel, episodeLine } from '@/lib/episodes';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { playerUrl } from '@/player/playerUrl';
import { S } from '@/strings.es';
import styles from './HomePage.module.css';

export function HomePage() {
  useDocumentTitle(S.titles.home);
  const home = useQuery(homeQuery());
  return (
    <div className="page">
      <h1 className="visually-hidden">{S.titles.home}</h1>
      {home.isPending ? (
        <>
          <SkeletonRow />
          <SkeletonRow />
        </>
      ) : home.isError ? (
        <ErrorState message={errorMessage(home.error)} onRetry={() => void home.refetch()} />
      ) : (
        <HomeRows home={home.data} />
      )}
    </div>
  );
}

function HomeRows({ home }: { home: HomeResponse }) {
  if (home.library.empty) return <EmptyLibrary />;
  const { continue_watching, recently_added } = home;
  if (continue_watching.length + recently_added.length === 0) {
    return (
      <EmptyState message={S.home.nothingToShow}>
        <Link to="/films" className="button">
          {S.nav.films}
        </Link>
        <Link to="/shows" className="button">
          {S.nav.shows}
        </Link>
      </EmptyState>
    );
  }
  return (
    <>
      {continue_watching.length > 0 && (
        <Row title={S.home.continueWatching}>
          {continue_watching.map((item) =>
            item.type === 'film' ? (
              <ContinueFilm key={`film-${item.id}`} film={item} />
            ) : (
              <ContinueEpisode key={`episode-${item.id}`} episode={item} />
            ),
          )}
        </Row>
      )}
      {recently_added.length > 0 && (
        <Row title={S.home.recentlyAdded}>
          {recently_added.map((item) => (
            <SummaryCard key={`${item.type}-${item.id}`} item={item} />
          ))}
        </Row>
      )}
    </>
  );
}

function DetailsLink({ to, title }: { to: string; title: string }) {
  return (
    <Link to={to} className={styles.details} aria-label={S.home.detailsOf(title)}>
      {S.home.details}
    </Link>
  );
}

function ContinueFilm({ film }: { film: FilmSummary }) {
  const p = film.progress;
  return (
    <div className={styles.item}>
      <PosterCard
        to={playerUrl('film', film.id)}
        title={film.title}
        year={film.year}
        poster={film.poster}
        progress={p ? { position: p.position_seconds, duration: p.duration_seconds } : null}
        myRating={film.my_rating}
      />
      <DetailsLink to={`/films/${film.id}`} title={film.title} />
    </div>
  );
}

function ContinueEpisode({ episode }: { episode: HomeEpisode }) {
  const p = episode.progress;
  return (
    <div className={cx(styles.item, styles.episodeItem)}>
      <EpisodeCard
        to={playerUrl('episode', episode.id)}
        still={episode.still}
        showTitle={episode.show_title}
        line={episodeLine(episode)}
        ariaLabel={episodeLabel(episode)}
        progress={p ? { position: p.position_seconds, duration: p.duration_seconds } : null}
      />
      <DetailsLink to={`/shows/${episode.show_id}`} title={episode.show_title} />
    </div>
  );
}
