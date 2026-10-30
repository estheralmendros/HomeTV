// "Mi lista" /watchlist (watchlist prd2 FR-W8–W10): URL-driven type filter and sort, infinite list
// of 50-item pages (sentinel + "Cargar más" fallback), the library's cards and badges, a single
// <h1> with the count once page 1 has loaded, empty/filtered/error states.
import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo, type ReactNode } from 'react';
import { errorMessage } from '@/api/errors';
import { nextPageParam } from '@/api/paging';
import { queryKeys, staleTimes } from '@/api/queryKeys';
import type { FilmSummary, ShowSummary } from '@/api/types';
import { getWatchlist, WATCHLIST_PAGE_SIZE } from '@/api/watchlist';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { LoadMoreFooter } from '@/components/LoadMoreFooter';
import { SkeletonGrid } from '@/components/Skeleton';
import { PosterGrid } from '@/features/library/PosterGrid';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useInfiniteScrollSentinel } from '@/lib/useInfiniteScrollSentinel';
import { S } from '@/strings.es';
import styles from './WatchlistPage.module.css';
import { WatchlistToolbar } from './WatchlistToolbar';
import { useWatchlistParams } from './useWatchlistParams';

export function WatchlistPage() {
  useDocumentTitle(S.titles.watchlist);
  const watchlist = useWatchlistParams();
  const { type, sort } = watchlist.params;
  const isAdmin = useIsAdmin();
  const list = useInfiniteQuery({
    queryKey: queryKeys.watchlist({ type, sort }),
    queryFn: ({ pageParam, signal }) =>
      getWatchlist({ ...watchlist.query, page: pageParam, page_size: WATCHLIST_PAGE_SIZE }, signal),
    initialPageParam: 1,
    getNextPageParam: nextPageParam,
    staleTime: staleTimes.watchlist,
  });
  const canLoadMore = list.hasNextPage && !list.isFetchingNextPage && !list.isFetchNextPageError;
  const sentinelRef = useInfiniteScrollSentinel({ onReach: () => void list.fetchNextPage(), enabled: canLoadMore });

  const total = list.data?.pages[0]?.total;
  const items = useMemo(() => uniqueItems(list.data?.pages.flatMap((p) => p.items.map((e) => e.item)) ?? []), [list.data]);

  let body: ReactNode;
  if (list.isPending) body = <SkeletonGrid />;
  else if (list.isError && !list.data) body = <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />;
  else if (items.length === 0)
    body =
      type === 'all' ? (
        <EmptyState message={S.watchlist.empty} />
      ) : (
        <EmptyState message={S.watchlist.noMatch[type]}>
          <button type="button" className="button" onClick={watchlist.showAll}>
            {S.watchlist.showAll}
          </button>
        </EmptyState>
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
      <h1 className={styles.header}>{total === undefined ? S.watchlist.title : S.watchlist.heading(total)}</h1>
      <WatchlistToolbar watchlist={watchlist} />
      {body}
    </div>
  );
}

/** Drops repeats by `${type}-${id}` (a title hidden between pages shifts one item onto the next page). */
function uniqueItems(items: ReadonlyArray<FilmSummary | ShowSummary>): Array<FilmSummary | ShowSummary> {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = `${item.type}-${item.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
