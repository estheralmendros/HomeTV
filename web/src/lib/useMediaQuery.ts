import { useCallback, useSyncExternalStore } from 'react';

/** Phone layout breakpoint (prd2 §5 step 6); keep in sync with the `max-width: 599px` CSS rules. */
export const PHONE_QUERY = '(max-width: 599px)';

/** Whether `query` matches now; re-renders when it starts or stops matching. False without matchMedia. */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window.matchMedia !== 'function') return () => {};
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    [query],
  );
  const getSnapshot = () => typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
  return useSyncExternalStore(subscribe, getSnapshot);
}
