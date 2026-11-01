// Committed location changes of a data router, for providers outside RouterProvider (the rating
// prompt host, ratings prd2 FR-W8). Only a new `location.key` fires the listener, never the
// `loading` states of a navigation. The change says how it happened (`action`) and where it came
// from (`previousPathname`), so a listener can tell a same-page REPLACE (search params only).
export type HistoryAction = 'POP' | 'PUSH' | 'REPLACE';

export interface LocationChange {
  pathname: string;
  previousPathname: string;
  action: HistoryAction;
}

interface RouterLocationState {
  historyAction: HistoryAction;
  location: { key: string; pathname: string };
}

interface LocationRouter {
  state: RouterLocationState;
  subscribe: (fn: (state: RouterLocationState) => void) => () => void;
}

export function locationSubscriber(router: LocationRouter) {
  return (listener: (change: LocationChange) => void) => {
    let { key, pathname } = router.state.location;
    return router.subscribe((s) => {
      if (s.location.key === key) return;
      const previousPathname = pathname;
      ({ key, pathname } = s.location);
      listener({ pathname, previousPathname, action: s.historyAction });
    });
  };
}

/** A REPLACE that keeps the pathname (only search params or state change): the same page. */
export const isSamePageReplace = (change: LocationChange) =>
  change.action === 'REPLACE' && change.pathname === change.previousPathname;
