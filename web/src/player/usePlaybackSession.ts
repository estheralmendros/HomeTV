// The playback session effects behind the player machine:
// - sends `POST playback` whenever the machine bumps `requestSeq` (initial, Retry, PLAY_FILE,
//   audio switch, recovery, resume after the pause release), with one retry on 422 invalid_audio_stream;
// - the `preparing` wait: PREPARE_TICK after Retry-After (the reducer applies the time cap);
// - attaches each decision (`attachSeq`) to the shell's <video> through attachSource, after
//   releasing the previous source (tracker snapshot → hls.destroy() / src reset);
// - maps element signals to machine events; fatal errors release the source first, so the
//   recovery request uses the tracked position (direct MEDIA_ERR_NETWORK checks the file first);
// - HLS keep-alive every 30 s (paused/blocked/buffering/ended; also playing on native HLS);
// - pause release: after 30 min of continuous pause, release + one DELETE + PAUSE_RELEASE;
// - exit (unmount or pagehide): release the element / DELETE the owned session (keepalive). A session handed over as
//   previous_session_id (the server stops it) is never kept alive or DELETEd again.
// Call useProgressReporter BEFORE this hook: its final report must run before the release.
import { useCallback, useEffect, useRef, type Dispatch } from 'react';
import { retryDelaySeconds } from '@/api/client';
import { isApiError } from '@/api/errors';
import { deleteHlsSession, getMediaFile, keepAliveHls, requestPlayback } from '@/api/playback';
import type { ClientCaps } from '@/api/types';
import { attachSource, playMedia, type AttachHandle, type HlsModule, type MediaSignal } from './attachSource';
import { getClientCaps, getPlatform, type Platform } from './capabilities';
import {
  acceptsMediaError,
  playerErrorFrom,
  type MediaErrorKind,
  type MediaErrorSource,
  type PlayerEvent,
  type PlayerState,
  type PlayerStateName,
} from './playerMachine';
import type { PositionTracker } from './useProgressReporter';

export const KEEPALIVE_INTERVAL_MS = 30_000;
export const PAUSE_RELEASE_MS = 30 * 60_000;
export const PREPARING_RETRY_FALLBACK_S = 5;

export interface PlaybackSessionOptions {
  state: PlayerState;
  dispatch: Dispatch<PlayerEvent>;
  /** The shell's single <video> (PlayerShellContext). */
  video: HTMLVideoElement | null;
  tracker: PositionTracker;
  caps?: ClientCaps;
  platform?: Platform;
  now?: () => number;
  /** The hls.js chunk failed for good (reloadOnce gave up): show the "app was updated" screen. */
  onAppUpdated?: () => void;
  /** Test seam for the lazy hls.js import. */
  loadHls?: () => Promise<HlsModule | undefined>;
}

export interface PlaybackSession {
  /** The app's play action (Play, Space/K, "Toca para reproducir", "Volver a reproducir"). */
  play: () => void;
  pause: () => void;
  switchAudio: (audioStreamIndex: number) => void;
  retry: () => void;
  /** An alternative version from the unsupported/error screen. */
  playFile: (fileId: number, startPositionSeconds: number) => void;
  /** [Cancelar] of the preparing overlay. */
  cancel: () => void;
}

const REQUEST_STATES: ReadonlySet<PlayerStateName> = new Set(['requesting', 'switching', 'recovering']);
const KEEPALIVE_STATES: ReadonlySet<PlayerStateName> = new Set(['paused', 'blocked', 'buffering', 'ended']);
const PAUSED_STATES: ReadonlySet<PlayerStateName> = new Set(['paused', 'blocked', 'ended']);
const RELEASE_ON_ENTRY: ReadonlySet<PlayerStateName> = new Set(['error', 'unsupported', 'exited']);

const ignore = () => undefined;

export function usePlaybackSession(options: PlaybackSessionOptions): PlaybackSession {
  const { state, dispatch, video, tracker } = options;
  const caps = options.caps ?? getClientCaps();
  const platform = options.platform ?? getPlatform();
  const now = options.now ?? Date.now;

  // Latest values for callbacks and async continuations.
  const latest = useRef({ state, caps, platform, now, onAppUpdated: options.onAppUpdated, loadHls: options.loadHls });
  latest.current = { state, caps, platform, now, onAppUpdated: options.onAppUpdated, loadHls: options.loadHls };

  const handleRef = useRef<AttachHandle | null>(null);
  /** The HLS session this mount must DELETE on exit (null once released or handed over). */
  const ownedSession = useRef<string | null>(null);
  /** Sessions whose keep-alive answered 404. */
  const lostSessions = useRef(new Set<string>());
  const mount = useRef(new AbortController());
  // StrictMode (dev) runs a simulated unmount + remount: the exit cleanup aborted the mount's
  // controller, so a remount needs a fresh one. Declared first, so it runs before every other
  // effect of this hook.
  useEffect(() => {
    if (mount.current.signal.aborted) mount.current = new AbortController();
  }, []);

  const releaseSource = useCallback(() => {
    const handle = handleRef.current;
    if (!handle) return;
    if (video) tracker.release(video);
    handle.detach();
    handleRef.current = null;
  }, [video, tracker]);

  const deleteOwned = useCallback((keepalive: boolean) => {
    const id = ownedSession.current;
    ownedSession.current = null;
    if (id) deleteHlsSession(id, { keepalive }).catch(ignore);
  }, []);

  const mediaError = useCallback(
    (source: MediaErrorSource, kind: MediaErrorKind) => {
      if (mount.current.signal.aborted) return;
      dispatch({ type: 'MEDIA_ERROR', now: latest.current.now(), source, kind, position: tracker.position });
    },
    [dispatch, tracker],
  );

  const handleFatal = useCallback(
    (signal: Extract<MediaSignal, { type: 'FATAL' }>) => {
      const current = latest.current.state;
      if (!acceptsMediaError(current.name)) return; // e.g. the old source while switching
      releaseSource();
      if (signal.source === 'direct') {
        if (signal.kind === 'network' && current.fileId != null) {
          // The drive may have been unplugged: 404 → gone, anything else → recovery.
          getMediaFile(current.fileId, mount.current.signal).then(
            () => mediaError('direct', 'network'),
            (err: unknown) => mediaError('direct', isApiError(err) && err.status === 404 ? 'gone' : 'network'),
          );
          return;
        }
      } else {
        deleteOwned(false); // the failed HLS session is replaced by the recovery request
      }
      mediaError(signal.source, signal.kind);
    },
    [releaseSource, deleteOwned, mediaError],
  );

  const onSignal = useCallback(
    (signal: MediaSignal) => {
      if (signal.type === 'APP_UPDATED') latest.current.onAppUpdated?.();
      else if (signal.type === 'FATAL') handleFatal(signal);
      else dispatch({ type: signal.type });
    },
    [dispatch, handleFatal],
  );

  // --- POST playback --------------------------------------------------------------------
  useEffect(() => {
    const s = latest.current.state;
    const pending = s.pendingRequest;
    if (!pending || !REQUEST_STATES.has(s.name)) return;
    const { intent } = pending;
    // The server stops previous_session_id whatever the answer; never touch it again.
    if (intent.previousSessionId && ownedSession.current === intent.previousSessionId) ownedSession.current = null;
    const controller = new AbortController();
    void (async () => {
      let audio = intent.audioStreamIndex;
      for (let attempt = 0; ; attempt += 1) {
        try {
          const response = await requestPlayback(
            intent.fileId,
            {
              client_caps: latest.current.caps,
              audio_stream_index: audio,
              start_position_seconds: Math.max(0, intent.startPositionSeconds),
              previous_session_id: intent.previousSessionId,
            },
            controller.signal,
          );
          if (controller.signal.aborted) return;
          if (response.mode === 'hls') {
            ownedSession.current = response.session_id;
            dispatch({ type: 'DECISION_HLS', response });
          } else if (response.mode === 'direct') {
            dispatch({ type: 'DECISION_DIRECT', response });
          } else {
            console.warn(`[player] unsupported: ${response.reason}: ${response.detail}`);
            dispatch({ type: 'DECISION_UNSUPPORTED', response });
          }
          return;
        } catch (err) {
          if (controller.signal.aborted) return;
          if (isApiError(err, 'invalid_audio_stream') && audio !== null && attempt === 0) {
            audio = null;
            continue;
          }
          if (isApiError(err, 'preparing')) {
            const estimate = err.details.estimated_seconds;
            dispatch({
              type: 'PREPARING',
              now: latest.current.now(),
              estimatedSeconds: typeof estimate === 'number' ? estimate : null,
              retryAfterSeconds: retryDelaySeconds(err, PREPARING_RETRY_FALLBACK_S),
            });
            return;
          }
          if (isApiError(err)) {
            const detail = typeof err.details.detail === 'string' ? `: ${err.details.detail}` : '';
            console.warn(`[player] playback request failed: ${err.code}${detail}`);
          }
          dispatch({ type: 'REQUEST_FAILED', error: playerErrorFrom(err) });
          return;
        }
      }
    })();
    return () => controller.abort();
  }, [state.requestSeq, dispatch]);

  // --- preparing wait -------------------------------------------------------------------
  const preparing = state.name === 'preparing';
  useEffect(() => {
    if (!preparing) return;
    const delayS = latest.current.state.prepareRetryDelayS ?? PREPARING_RETRY_FALLBACK_S;
    const timer = setTimeout(() => dispatch({ type: 'PREPARE_TICK', now: latest.current.now() }), delayS * 1000);
    return () => clearTimeout(timer);
  }, [preparing, state.requestSeq, dispatch]);

  // --- attach each decision -------------------------------------------------------------
  useEffect(() => {
    const s = latest.current.state;
    if (s.attachSeq === 0 || s.name !== 'loading' || !video || !s.mode || !s.url) return;
    releaseSource();
    const gen = tracker.beginAttach(s.attachStart, { duration: s.durationSeconds, resetPosition: s.attachResetsPosition });
    handleRef.current = attachSource({
      video,
      response: { mode: s.mode, url: s.url },
      startPosition: s.attachStart,
      platform: latest.current.platform,
      signal: mount.current.signal,
      loadHls: latest.current.loadHls,
      onMediaEvent: (type) => tracker.handle(gen, type, video),
      onSignal: (signal) => {
        if (gen === tracker.gen) onSignal(signal);
      },
    });
  }, [state.attachSeq, video, tracker, releaseSource, onSignal]);

  // --- pause the old source while an audio switch is requested (step 8.1) ---------------
  const switching = state.name === 'switching';
  useEffect(() => {
    if (switching && handleRef.current && video && !video.paused) video.pause();
  }, [switching, video]);

  // --- resume the old source after a failed/cancelled switch in direct mode -------------
  useEffect(() => {
    const s = latest.current.state;
    if (s.resumeSeq === 0 || s.name !== 'playing' || !video || !handleRef.current) return;
    void playMedia(video, onSignal, handleRef.current.kind);
  }, [state.resumeSeq, video, onSignal]);

  // --- error / unsupported / exited: nothing should keep loading ------------------------
  const releaseOnEntry = RELEASE_ON_ENTRY.has(state.name);
  useEffect(() => {
    if (releaseOnEntry) releaseSource();
  }, [releaseOnEntry, releaseSource]);

  // --- HLS keep-alive -------------------------------------------------------------------
  const sessionId = state.mode === 'hls' && !state.sessionReleased ? state.sessionId : null;
  const keepAliveWanted =
    sessionId !== null && (KEEPALIVE_STATES.has(state.name) || (platform.nativeHls && state.name === 'playing'));
  useEffect(() => {
    if (!keepAliveWanted || !sessionId) return;
    const timer = setInterval(() => {
      if (lostSessions.current.has(sessionId) || ownedSession.current !== sessionId) return;
      keepAliveHls(sessionId).catch((err: unknown) => {
        // 404: the session is gone; the next play fails and triggers the recovery.
        if (isApiError(err) && err.status === 404) lostSessions.current.add(sessionId);
      });
    }, KEEPALIVE_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [keepAliveWanted, sessionId]);

  // --- Release after 30 min of continuous pause --------------------------------------------
  const pausedWithSession = sessionId !== null && PAUSED_STATES.has(state.name);
  useEffect(() => {
    if (!pausedWithSession) return;
    const timer = setTimeout(() => {
      releaseSource();
      deleteOwned(false);
      dispatch({ type: 'PAUSE_RELEASE' });
    }, PAUSE_RELEASE_MS);
    return () => clearTimeout(timer);
  }, [pausedWithSession, sessionId, releaseSource, deleteOwned, dispatch]);

  // --- exit -----------------------------------------------------------------------------
  // Closing the tab or a full navigation never unmounts React: pagehide DELETEs the owned
  // session (keepalive) so it doesn't hold a server slot until its idle timeout (step 14). The
  // source stays attached: if the page comes back from the bfcache, the next segment request
  // answers 404 and the normal recovery requests a new session at the tracked position.
  useEffect(() => {
    const onPageHide = () => deleteOwned(true);
    window.addEventListener('pagehide', onPageHide);
    return () => window.removeEventListener('pagehide', onPageHide);
  }, [deleteOwned]);

  useEffect(() => {
    return () => {
      mount.current.abort();
      releaseSource();
      deleteOwned(true);
    };
  }, [releaseSource, deleteOwned]);

  // --- actions --------------------------------------------------------------------------
  const play = useCallback(() => {
    const s = latest.current.state;
    if (!s.sessionReleased && s.name === 'ended' && video) video.currentTime = 0;
    dispatch({ type: 'PLAY', position: tracker.position });
    const handle = handleRef.current;
    if (!s.sessionReleased && handle && video) void playMedia(video, onSignal, handle.kind);
  }, [dispatch, tracker, video, onSignal]);

  const pause = useCallback(() => video?.pause(), [video]);

  const switchAudio = useCallback(
    (audioStreamIndex: number) => {
      if (video) tracker.snapshot(video);
      dispatch({ type: 'SWITCH_AUDIO', audioStreamIndex, t: tracker.position });
    },
    [dispatch, tracker, video],
  );

  const retry = useCallback(() => dispatch({ type: 'RETRY', position: tracker.position }), [dispatch, tracker]);

  const playFile = useCallback(
    (fileId: number, startPositionSeconds: number) => dispatch({ type: 'PLAY_FILE', fileId, startPositionSeconds }),
    [dispatch],
  );

  const cancel = useCallback(() => dispatch({ type: 'CANCEL' }), [dispatch]);

  return { play, pause, switchAudio, retry, playFile, cancel };
}
