// Global search (prd2 §6): GET /api/search?q=&limit=20 when q.trim() is not empty; films and
// shows sections (hidden when empty). The server does accent/case folding; the UI only trims.
import { useQuery } from '@tanstack/react-query';
import { useId } from 'react';
import { useSearchParams } from 'react-router-dom';
import { errorMessage } from '@/api/errors';
import { searchQuery } from '@/api/queries';
import type { FilmSummary, ShowSummary } from '@/api/types';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { SkeletonGrid } from '@/components/Skeleton';
import { PosterGrid } from '@/features/library/PosterGrid';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { COMPACT_NAV_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { S } from '@/strings.es';
import { NavSearch } from './NavSearch';
import styles from './SearchPage.module.css';

export function SearchPage() {
  useDocumentTitle(S.titles.search);
  const [searchParams] = useSearchParams();
  const q = (searchParams.get('q') ?? '').trim();
  // Below 720 px the NavBar has no search field, so the page brings its own.
  const compact = useMediaQuery(COMPACT_NAV_QUERY);
  return (
    <div className="page">
      <h1>{S.titles.search}</h1>
      {compact && <NavSearch variant="page" />}
      {q ? <SearchResults q={q} /> : <EmptyState message={S.search.hint} />}
    </div>
  );
}

function SearchResults({ q }: { q: string }) {
  const search = useQuery(searchQuery(q));
  const isAdmin = useIsAdmin();
  if (search.isPending) return <SkeletonGrid />;
  if (search.isError) return <ErrorState message={errorMessage(search.error)} onRetry={() => void search.refetch()} />;
  const { films, shows } = search.data;
  if (films.length === 0 && shows.length === 0) return <EmptyState message={S.search.noResults(q)} />;
  return (
    <>
      {films.length > 0 && <ResultSection title={S.search.films(films.length)} items={films} isAdmin={isAdmin} />}
      {shows.length > 0 && <ResultSection title={S.search.shows(shows.length)} items={shows} isAdmin={isAdmin} />}
    </>
  );
}

function ResultSection({ title, items, isAdmin }: { title: string; items: Array<FilmSummary | ShowSummary>; isAdmin: boolean }) {
  const headingId = useId();
  return (
    <section className={styles.section} aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.heading}>
        {title}
      </h2>
      <PosterGrid items={items} showUnmatchedTags={isAdmin} />
    </section>
  );
}
