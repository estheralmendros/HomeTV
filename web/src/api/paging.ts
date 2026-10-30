// Shared paging helper for the infinite lists (library grids, "Mi lista").
import type { Page } from './types';

/** Next page number for `useInfiniteQuery`, or undefined after the last page. */
export function nextPageParam<T>(last: Page<T>): number | undefined {
  return last.page * last.page_size < last.total ? last.page + 1 : undefined;
}
