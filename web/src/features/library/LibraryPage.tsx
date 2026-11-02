// Library grids /films and /shows: URL-driven toolbar, infinite list of 60-item pages
// (sentinel 600 px before the end, "Cargar más" fallback), empty/filtered/error states.
import { useInfiniteQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { LIBRARY_PAGE_SIZE, listFilms, listShows } from '@/api/catalog';
import { errorMessage } from '@/api/errors';
import { nextPageParam } from '@/api/paging';
import { queryKeys, staleTimes } from '@/api/queryKeys';
import type { FilmSummary, LibraryQuery, LibraryType, Page, ShowSummary } from '@/api/types';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { EmptyLibrary } from '@/components/EmptyLibrary';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { LoadMoreFooter } from '@/components/LoadMoreFooter';
import { SkeletonGrid } from '@/components/Skeleton';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useInfiniteScrollSentinel } from '@/lib/useInfiniteScrollSentinel';
import { S } from '@/strings.es';
import styles from './Library.module.css';
import { LibraryToolbar } from './LibraryToolbar';
import { PosterGrid } from './PosterGrid';
import { useLibraryParams } from './useLibraryParams';

type LibraryPageData = Page<FilmSummary | ShowSummary>;

function fetchPage(type: LibraryType, query: LibraryQuery, signal: AbortSignal): Promise<LibraryPageData> {
  return type === 'films' ? listFilms(query, signal) : listShows(query, signal);
}

export function LibraryPage({ type }: { type: LibraryType }) {
  const title = type === 'films' ? S.titles.films : S.titles.shows;
  useDocumentTitle(title);
  const library = useLibraryParams();
  const isAdmin = useIsAdmin();
  const list = useInfiniteQuery({
    queryKey: queryKeys.library(type, library.query),
    queryFn: ({ pageParam, signal }) =>
      fetchPage(type, { ...library.query, page: pageParam, page_size: LIBRARY_PAGE_SIZE }, signal),
    initialPageParam: 1,
    getNextPageParam: nextPageParam,
    staleTime: staleTimes.library,
    enabled: !library.yearRangeInvalid,
  });
  const canLoadMore = list.hasNextPage && !list.isFetchingNextPage && !list.isFetchNextPageError;
  const sentinelRef = useInfiniteScrollSentinel({ onReach: () => void list.fetchNextPage(), enabled: canLoadMore });

  const total = list.data?.pages[0]?.total;
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];

  let body: ReactNode;
  if (library.yearRangeInvalid) body = null;
  else if (list.isPending) body = <SkeletonGrid />;
  else if (list.isError && !list.data) body = <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />;
  else if (items.length === 0)
    body = library.hasFilters ? (
      <EmptyState message={S.library.noMatch[type]}>
        <button type="button" className="button" onClick={library.clearFilters}>
          {S.library.clearFilters}
        </button>
      </EmptyState>
    ) : (
      <EmptyLibrary />
    );
  else
    body = (
      <>
        <PosterGrid items={items} showUnmatchedTags={isAdmin} />
        <LoadMoreFooter list={list} sentinelRef={sentinelRef} />
      </>
    );

  return (
    <div className="page">
      <div className={styles.header}>
        <h1>{title}</h1>
        {total !== undefined && !library.yearRangeInvalid && <p className={styles.count}>{S.library.count(total, type)}</p>}
      </div>
      <LibraryToolbar type={type} library={library} />
      {body}
    </div>
  );
}
