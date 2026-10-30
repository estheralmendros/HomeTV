// Footer after an infinite PosterGrid (library grids, Mi lista): it holds the scroll sentinel and the
// "Cargar más" fallback button, or the load-more error with "Reintentar".
import type { Ref } from 'react';
import { S } from '@/strings.es';
import styles from './LoadMoreFooter.module.css';

/** The parts of a `useInfiniteQuery` result the footer reads. */
export interface LoadMoreList {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  isFetchNextPageError: boolean;
  fetchNextPage: () => Promise<unknown>;
}

export function LoadMoreFooter({ list, sentinelRef }: { list: LoadMoreList; sentinelRef: Ref<HTMLDivElement> }) {
  return (
    <div className={styles.more} ref={sentinelRef}>
      {list.isFetchNextPageError ? (
        <>
          <p className={styles.moreError} role="alert">
            {S.common.loadMoreError}
          </p>
          <button type="button" className="button" onClick={() => void list.fetchNextPage()}>
            {S.common.retry}
          </button>
        </>
      ) : (
        list.hasNextPage && (
          <button type="button" className="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
            {S.common.loadMore}
          </button>
        )
      )}
    </div>
  );
}
