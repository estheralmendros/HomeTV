import { useCallback, useSyncExternalStore } from 'react';

/** Phone layout breakpoint (prd2 §5 step 6); keep in sync with the `max-width: 599px` CSS rules. */
export const PHONE_QUERY = '(max-width: 599px)';

/**
 * Compact navigation breakpoint (prd2 §5): below 720 px the NavBar drops its section links and
 * search for the bottom MobileTabBar, and /search shows its own input. Keep in sync with the
 * `max-width: 719px` / `min-width: 720px` CSS rules (tab bar padding, toasts).
 */
export const COMPACT_NAV_QUERY = '(max-width: 719px)';

/**
 * Search icon breakpoint (ratings prd2 FR-W15): below 960 px the bar shows a "Buscar" magnifier link
 * instead of the NavSearch field (five section links leave no room for it from 720 to 959 px), and
 * /search shows its own input. Keep in sync with the `max-width: 959px` CSS rules.
 */
export const SEARCH_ICON_QUERY = '(max-width: 959px)';

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
