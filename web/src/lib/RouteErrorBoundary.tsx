// Root errorElement: a stale lazy chunk reloads once; when that already happened,
// the "app was updated" message; any other error "Algo ha ido mal.". Never a blank screen.
import { useState } from 'react';
import { useRouteError } from 'react-router-dom';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenSpinner } from '@/components/Spinner';
import { S } from '@/strings.es';
import { clearChunkReloadMarker, isChunkLoadError, reloadOnce, type ReloadOutcome } from './chunkReload';
import { reloader } from './reload';
import { useDocumentTitle } from './useDocumentTitle';

/** One reloadOnce() per error object, however often the boundary renders. */
const outcomes = new WeakMap<object, ReloadOutcome>();

function outcomeFor(error: unknown): ReloadOutcome | null {
  if (!isChunkLoadError(error)) return null;
  const key = error as object;
  let outcome = outcomes.get(key);
  if (outcome === undefined) {
    outcome = reloadOnce();
    outcomes.set(key, outcome);
  }
  return outcome;
}

export function RouteErrorBoundary() {
  const error = useRouteError();
  const [outcome] = useState(() => outcomeFor(error));
  useDocumentTitle(S.titles.error);

  if (outcome === 'reloading') return <FullScreenSpinner />;
  if (outcome === 'gave_up') {
    return (
      <ErrorState message={S.routeError.appUpdated}>
        <button
          type="button"
          className="button button-primary"
          onClick={() => {
            clearChunkReloadMarker();
            reloader.reload();
          }}
        >
          {S.routeError.reload}
        </button>
      </ErrorState>
    );
  }
  console.error('[route] unexpected error', error);
  return (
    <ErrorState message={S.routeError.generic}>
      <button type="button" className="button button-primary" onClick={() => reloader.reload()}>
        {S.routeError.reload}
      </button>
      {/* A plain link: the router state may be broken, so leave through a full navigation. */}
      <a href="/" className="button">
        {S.routeError.goHome}
      </a>
    </ErrorState>
  );
}
