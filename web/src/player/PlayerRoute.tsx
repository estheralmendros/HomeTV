// Stable player shell for /watch/:type/:id. Not keyed: React
// Router keeps it mounted across next-episode / next-part navigations, so the fullscreen container
// and the single <video playsinline preload="metadata"> survive them. PlayerPage (all per-playback
// state) is keyed by pathname + `file` only, never by `t`.
// The shell also owns the subtitle look: one generated `::cue` rule (the panel draft while it is
// open, else the profile's saved style), the draft store and `--video-h` (the phone sample line's raise).
import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { useLocation, useParams, useSearchParams } from 'react-router-dom';
import { useMeQuery } from '@/auth/useCurrentProfile';
import { NotFound } from '@/components/NotFound';
import { useRatingPrompt } from '@/features/ratings/ratingPrompt';
import { parseRouteId } from '@/lib/routeId';
import { useElementHeight } from '@/lib/useElementHeight';
import styles from './Player.module.css';
import { PlayerPage } from './PlayerPage';
import { cueCss, subtitleStyleOf } from './subtitleStyle';
import { createSubtitleStyleDraft, type SubtitleStyleDraft } from './subtitleStyleDraft';

export type PlayerItemType = 'film' | 'episode';

export function parsePlayerType(value: string | undefined): PlayerItemType | null {
  return value === 'film' || value === 'episode' ? value : null;
}

export interface PlayerShell {
  /** The fullscreen container (never inside the keyed PlayerPage). */
  rootEl: HTMLDivElement;
  /** The single media element, reused by every PlayerPage mount. */
  videoEl: HTMLVideoElement;
  /** The style panel's draft (read with useSyncExternalStore by leaf components only). */
  subtitleStyleDraft: SubtitleStyleDraft;
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

/** `<style data-subtitle-style>`: the `::cue` rule of the draft, else of the saved style. */
function SubtitleStyleElement({ draft }: { draft: SubtitleStyleDraft }) {
  const saved = subtitleStyleOf(useMeQuery().data);
  const current = useSyncExternalStore(draft.subscribe, draft.get) ?? saved;
  return <style data-subtitle-style="">{cueCss(current, `.${styles.video}`)}</style>;
}

/** Sets `--video-h` on the shell root (a leaf: a resize re-renders only this). */
function VideoHeightVar({ video, root }: { video: HTMLVideoElement; root: HTMLDivElement }) {
  const height = useElementHeight(video);
  useEffect(() => {
    root.style.setProperty('--video-h', `${height}px`);
  }, [root, height]);
  return null;
}

export function PlayerRoute() {
  const params = useParams();
  const location = useLocation();
  const [search] = useSearchParams();
  const [rootEl, setRootEl] = useState<HTMLDivElement | null>(null);
  const [videoEl, setVideoEl] = useState<HTMLVideoElement | null>(null);
  const [subtitleStyleDraft] = useState(createSubtitleStyleDraft);
  const shell = useMemo<PlayerShell | null>(
    () => (rootEl && videoEl ? { rootEl, videoEl, subtitleStyleDraft } : null),
    [rootEl, videoEl, subtitleStyleDraft],
  );
  // No rating prompt dialog during playback: the host waits for the release.
  const { suppress } = useRatingPrompt();
  useEffect(() => suppress(), [suppress]);
  const type = parsePlayerType(params.type);
  const id = parseRouteId(params.id);
  if (type === null || id === null) return <NotFound />;

  return (
    <div ref={setRootEl} className={styles.root} data-testid="player-root">
      <SubtitleStyleElement draft={subtitleStyleDraft} />
      <video ref={setVideoEl} className={styles.video} playsInline preload="metadata" />
      {shell && (
        <PlayerShellContext.Provider value={shell}>
          <VideoHeightVar video={shell.videoEl} root={shell.rootEl} />
          <PlayerPage key={playerPageKey(location.pathname, search)} type={type} id={id} />
        </PlayerShellContext.Provider>
      )}
    </div>
  );
}
