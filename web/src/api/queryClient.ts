import { QueryClient, type QueryKey } from '@tanstack/react-query';
import { ApiError } from './errors';
import { invalidateAfter, type InvalidationEvent } from './queryKeys';

/** Retry network failures and 5xx only, at most twice. */
export function shouldRetry(failureCount: number, err: unknown): boolean {
  return failureCount < 2 && err instanceof ApiError && (err.status === 0 || err.status >= 500);
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      // networkMode stays the default 'online': offline, queries pause (detail pages keep their
      // skeleton). Exception: personQuery sets 'always' so the actor page shows the connection
      // error with "Reintentar" offline instead of a skeleton that never ends.
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        retry: shouldRetry,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  });
}

/** Invalidates every key of the event (prefix match). */
export async function runInvalidation(queryClient: QueryClient, event: InvalidationEvent): Promise<void> {
  await Promise.all(invalidateAfter(event).map((queryKey: QueryKey) => queryClient.invalidateQueries({ queryKey })));
}
