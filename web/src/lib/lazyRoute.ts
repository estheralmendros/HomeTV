import type { ComponentType } from 'react';
import type { LazyRouteFunction, RouteObject } from 'react-router-dom';

/**
 * React Router `lazy` for a route module. When the `vite:preloadError` handler prevented the
 * error, the import resolves to `undefined` while the page reloads (§13): wait forever instead
 * of rendering a broken route.
 */
export function lazyRoute<M>(load: () => Promise<M>, pick: (module: M) => ComponentType): LazyRouteFunction<RouteObject> {
  return async () => {
    const module = await load();
    if (module === undefined) return new Promise<never>(() => undefined);
    return { Component: pick(module) };
  };
}
