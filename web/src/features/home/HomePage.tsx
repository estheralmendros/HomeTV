// Home: up to five rows in a fixed order: Seguir viendo and Añadidos recientemente (GET /api/home),
// Mi lista (the watchlist's newest 20) and Películas / Series (the newest 20 of each library), each of the
// last three ending with a "Ver todo" card. The four queries start together on mount; /api/home alone
// decides the page state (skeletons, error, empty library, nothing to show). A new row that is pending
// shows one skeleton row, and one that is empty or failed (without data) is hidden.
// Continue Watching holds in-progress items and each started show's Next Up episode
// (no progress → no bar). Its cards open the player WITHOUT ?file=: the player
// runs selectVersion on the home payload (multi-part and version-match rules), never a stale version choice.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { homeQuery, libraryHomeRowQuery, watchlistHomeRowQuery } from '@/api/queries';
import { errorMessage } from '@/api/errors';
import type { FilmSummary, HomeEpisode, HomeResponse, Page, ShowSummary, WatchlistEntry } from '@/api/types';
import { EmptyLibrary } from '@/components/EmptyLibrary';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { Row } from '@/components/Row';
import { SeeAllCard } from '@/components/SeeAllCard';
import { SkeletonRow } from '@/components/Skeleton';
import { SummaryCard } from '@/components/SummaryCard';
import { WideCard } from '@/components/WideCard';
import { cx } from '@/lib/cx';
import { episodeLabel, episodeLine } from '@/lib/episodes';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { playerUrl } from '@/player/playerUrl';
import { S } from '@/strings.es';
import styles from './HomePage.module.css';

export function HomePage() {
  useDocumentTitle(S.titles.home);
  const home = useQuery(homeQuery());
  const watchlist = useQuery(watchlistHomeRowQuery());
  const films = useQuery(libraryHomeRowQuery('films'));
  const shows = useQuery(libraryHomeRowQuery('shows'));
  const retry = () => {
    void home.refetch();
    for (const row of [watchlist, films, shows]) if (row.isError) void row.refetch();
  };
  return (
    <div className="page">
      <h1 className="visually-hidden">{S.titles.home}</h1>
      {home.isPending ? (
        <>
          <SkeletonRow />
          <SkeletonRow />
        </>
      ) : home.isError ? (
        <ErrorState message={errorMessage(home.error)} onRetry={retry} />
      ) : (
        <HomeRows home={home.data} watchlist={watchlist} films={films} shows={shows} />
      )}
    </div>
  );
}

interface HomeRowsProps {
  home: HomeResponse;
  watchlist: UseQueryResult<Page<WatchlistEntry>>;
  films: UseQueryResult<Page<FilmSummary> | Page<ShowSummary>>;
  shows: UseQueryResult<Page<FilmSummary> | Page<ShowSummary>>;
}

const summaryCard = (item: FilmSummary | ShowSummary) => <SummaryCard key={`${item.type}-${item.id}`} item={item} />;

function HomeRows({ home, watchlist, films, shows }: HomeRowsProps) {
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
      <ExtraRow title={S.home.watchlist} to="/watchlist" query={watchlist}>
        {(entry) => <SummaryCard key={`${entry.kind}-${entry.item.id}`} item={entry.item} />}
      </ExtraRow>
      {recently_added.length > 0 && (
        <Row title={S.home.recentlyAdded}>
          {recently_added.map(summaryCard)}
        </Row>
      )}
      <ExtraRow<FilmSummary | ShowSummary> title={S.home.films} to="/films?sort=added" query={films}>
        {summaryCard}
      </ExtraRow>
      <ExtraRow<FilmSummary | ShowSummary> title={S.home.shows} to="/shows?sort=added" query={shows}>
        {summaryCard}
      </ExtraRow>
    </>
  );
}

interface ExtraRowProps<T> {
  title: string;
  /** Target of the trailing "Ver todo" card. */
  to: string;
  query: { isPending: boolean; data: { items: readonly T[] } | undefined };
  children: (item: T) => ReactNode;
}

/** A row fed by its own query: skeleton while pending, hidden when empty or failed without data. */
function ExtraRow<T>({ title, to, query, children }: ExtraRowProps<T>) {
  if (query.isPending) return <SkeletonRow />;
  const items = query.data?.items ?? [];
  if (items.length === 0) return null;
  return (
    <Row title={title}>
      {items.map(children)}
      <SeeAllCard to={to} rowTitle={title} />
    </Row>
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
    <div className={cx(styles.item, styles.wideItem)}>
      <WideCard
        to={playerUrl('film', film.id)}
        image={film.backdrop}
        kind="backdrop"
        title={film.title}
        line={film.year !== null ? String(film.year) : null}
        ariaLabel={S.common.titleWithYear(film.title, film.year)}
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
    <div className={cx(styles.item, styles.wideItem)}>
      <WideCard
        to={playerUrl('episode', episode.id)}
        image={episode.still}
        kind="still"
        title={episode.show_title}
        line={episodeLine(episode)}
        ariaLabel={episodeLabel(episode)}
        progress={p ? { position: p.position_seconds, duration: p.duration_seconds } : null}
      />
      <DetailsLink to={`/shows/${episode.show_id}`} title={episode.show_title} />
    </div>
  );
}
