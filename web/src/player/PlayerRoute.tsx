// Stable player shell for /watch/:type/:id. Not keyed: React
// Router keeps it mounted across next-episode / next-part navigations, so the fullscreen container
// and the single <video playsinline preload="metadata"> survive them. PlayerPage (all per-playback
// state) is keyed by pathname + `file` only, never by `t`.
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { useLocation, useParams, useSearchParams } from 'react-router-dom';
import { NotFound } from '@/components/NotFound';
import { useRatingPrompt } from '@/features/ratings/ratingPrompt';
import { parseRouteId } from '@/lib/routeId';
import styles from './Player.module.css';
import { PlayerPage } from './PlayerPage';

export type PlayerItemType = 'film' | 'episode';

export function parsePlayerType(value: string | undefined): PlayerItemType | null {
  return value === 'film' || value === 'episode' ? value : null;
}

export interface PlayerShell {
  /** The fullscreen container (never inside the keyed PlayerPage). */
  rootEl: HTMLDivElement;
  /** The single media element, reused by every PlayerPage mount. */
  videoEl: HTMLVideoElement;
}

export const PlayerShellContext = createContext<PlayerShell | null>(null);

export function usePlayerShell(): PlayerShell {
  const shell = useContext(PlayerShellContext);
  if (!shell) throw new Error('usePlayerShell must be used inside <PlayerRoute>');
  return shell;
}

/** The PlayerPage key: pathname + `?file=` (never `t`). */
export function playerPageKey(pathname: string, search: URLSearchParams): string {
  return `${pathname}?file=${search.get('file') ?? ''}`;
}

export function PlayerRoute() {
  const params = useParams();
  const location = useLocation();
  const [search] = useSearchParams();
  const [rootEl, setRootEl] = useState<HTMLDivElement | null>(null);
  const [videoEl, setVideoEl] = useState<HTMLVideoElement | null>(null);
  const shell = useMemo<PlayerShell | null>(() => (rootEl && videoEl ? { rootEl, videoEl } : null), [rootEl, videoEl]);
  // No rating prompt dialog during playback: the host waits for the release.
  const { suppress } = useRatingPrompt();
  useEffect(() => suppress(), [suppress]);
  const type = parsePlayerType(params.type);
  const id = parseRouteId(params.id);
  if (type === null || id === null) return <NotFound />;

  return (
    <div ref={setRootEl} className={styles.root} data-testid="player-root">
      <video ref={setVideoEl} className={styles.video} playsInline preload="metadata" />
      {shell && (
        <PlayerShellContext.Provider value={shell}>
          <PlayerPage key={playerPageKey(location.pathname, search)} type={type} id={id} />
        </PlayerShellContext.Provider>
      )}
    </div>
  );
}
