// /activity (ratings prd2 FR-W12/13): the household's ratings, newest first, 50-item pages
// (sentinel + "Cargar más"), deduped by profile + title, refetched on every visit.
import { useInfiniteQuery } from '@tanstack/react-query';
import { useId, useMemo, type ReactNode } from 'react';
import { errorMessage } from '@/api/errors';
import { nextPageParam } from '@/api/paging';
import { queryKeys, staleTimes } from '@/api/queryKeys';
import { ACTIVITY_PAGE_SIZE, getActivity } from '@/api/ratings';
import type { ActivityEntry } from '@/api/types';
import { useCurrentProfile } from '@/auth/useCurrentProfile';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { LoadMoreFooter } from '@/components/LoadMoreFooter';
import { Skeleton } from '@/components/Skeleton';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useInfiniteScrollSentinel } from '@/lib/useInfiniteScrollSentinel';
import { S } from '@/strings.es';
import styles from './ActivityPage.module.css';
import { ActivityRow } from './ActivityRow';

export function ActivityPage() {
  useDocumentTitle(S.titles.activity);
  const headingId = useId();
  const me = useCurrentProfile();
  const list = useInfiniteQuery({
    queryKey: queryKeys.activity(),
    queryFn: ({ pageParam, signal }) => getActivity({ page: pageParam, page_size: ACTIVITY_PAGE_SIZE }, signal),
    initialPageParam: 1,
    getNextPageParam: nextPageParam,
    staleTime: staleTimes.activity,
    refetchOnMount: 'always',
  });
  const canLoadMore = list.hasNextPage && !list.isFetchingNextPage && !list.isFetchNextPageError;
  const sentinelRef = useInfiniteScrollSentinel({ onReach: () => void list.fetchNextPage(), enabled: canLoadMore });
  const entries = useMemo(() => uniqueEntries(list.data?.pages.flatMap((p) => p.items) ?? []), [list.data]);

  let body: ReactNode;
  if (list.isPending) body = <ActivitySkeleton />;
  else if (list.isError && !list.data) body = <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />;
  else if (entries.length === 0) body = <EmptyState message={S.activity.empty} />;
  else
    body = (
      <>
        <ol className={styles.list} aria-labelledby={headingId}>
          {entries.map((entry) => (
            <ActivityRow key={entryKey(entry)} entry={entry} own={entry.profile.id === me?.id} />
          ))}
        </ol>
        <LoadMoreFooter list={list} sentinelRef={sentinelRef} />
      </>
    );

  return (
    <div className="page">
      <h1 id={headingId} className={styles.header}>
        {S.activity.heading}
      </h1>
      {body}
    </div>
  );
}

const entryKey = (entry: ActivityEntry) => `${entry.profile.id}-${entry.kind}-${entry.item.id}`;

/** Drops repeats by profile + title (a rating changed between pages moves it to the top). First wins. */
function uniqueEntries(entries: readonly ActivityEntry[]): ActivityEntry[] {
  const seen = new Set<string>();
  return entries.filter((entry) => {
    const key = entryKey(entry);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function ActivitySkeleton() {
  return (
    <div className={styles.list} aria-hidden="true">
      {Array.from({ length: 6 }, (_, i) => (
        <Skeleton key={i} className={styles.skeletonRow} />
      ))}
    </div>
  );
}
