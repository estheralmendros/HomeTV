// One playback of one item/file (prd2 §9). Keyed by PlayerRoute on pathname + `?file=`, so the
// next episode / next part / an explicit `?file=` change remount it with a fresh machine,
// session, reporter and subtitle cache, while the shell's <video> and fullscreen root survive.
// Wiring (order matters: unmount cleanups run in declaration order):
//   start decision → LOAD → useProgressReporter → usePlaybackSession → UI.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type PointerEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { errorMessage, isApiError } from '@/api/errors';
import { getEpisode, getFilm } from '@/api/catalog';
import { queryKeys, staleTimes } from '@/api/queryKeys';
import type { EpisodeDetail, FilmDetail, ItemType, MediaFile } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { useCurrentProfile } from '@/auth/useCurrentProfile';
import { ErrorState } from '@/components/ErrorState';
import { Spinner } from '@/components/Spinner';
import { clearChunkReloadMarker } from '@/lib/chunkReload';
import { episodeLabel } from '@/lib/episodes';
import { reloader } from '@/lib/reload';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useGoBack } from '@/lib/useGoBack';
import { S } from '@/strings.es';
import { getClientCaps, getPlatform } from './capabilities';
import { Controls } from './Controls';
import { NextEpisodePrompt } from './NextEpisodePrompt';
import { NextPartPrompt } from './NextPartPrompt';
import styles from './Player.module.css';
import { canSwitchAudio, initialPlayerState, playerReducer, type PlayerStateName } from './playerMachine';
import { PlayerErrorScreen } from './PlayerErrorScreen';
import { usePlayerShell } from './PlayerRoute';
import { PlayerToasts, usePlayerToasts, type PlayerToast } from './PlayerToasts';
import { playerUrl } from './playerUrl';
import { PreparingOverlay } from './PreparingOverlay';
import { TapToPlayOverlay } from './TapToPlayOverlay';
import { AudioMenu, SubtitleMenu } from './TrackMenu';
import { UnsupportedMessage } from './UnsupportedMessage';
import { useAutoHide } from './useAutoHide';
import { useFullscreen } from './useFullscreen';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { mediaDuration, useMediaSelector } from './useMediaClock';
import { isWithinLead, useNextUp } from './useNextUp';
import { usePlaybackSession } from './usePlaybackSession';
import { usePlayerSubtitles } from './usePlayerSubtitles';
import { PositionTracker, useProgressReporter } from './useProgressReporter';
import { playedItemOf, useStartDecision, type DecisionSource, type PlayerItem } from './useStartDecision';
import { useVolume } from './useVolume';
import { playableAlternatives, startPosition } from './versions';

export interface PlayerPageProps {
  type: ItemType;
  id: number;
}

type Menu = 'subtitles' | 'audio' | null;

const SPINNER_STATES: ReadonlySet<PlayerStateName> = new Set(['idle', 'requesting', 'loading', 'recovering', 'switching', 'buffering']);
/** The element's position is not this playback's (released or replaced): show the tracked one. */
const TRACKED_TIME_STATES: ReadonlySet<PlayerStateName> = new Set(['requesting', 'preparing', 'switching', 'recovering']);
const SCREEN_STATES: ReadonlySet<PlayerStateName> = new Set(['error', 'unsupported', 'exited']);
const DOUBLE_TAP_MS = 300;

function parseStart(raw: string | null): number | null {
  if (raw === null || raw.trim() === '') return null;
  const t = Number(raw);
  return Number.isFinite(t) && t >= 0 ? t : null;
}

function parseFile(raw: string | null): number | null {
  if (raw === null) return null;
  const id = Number(raw);
  return Number.isInteger(id) && id > 0 ? id : null;
}

function playerTitle(type: ItemType, source: DecisionSource | undefined, file: MediaFile | null): string {
  if (!source) return S.titles.player;
  if (type === 'episode') return episodeLabel(source as EpisodeDetail);
  const film = source as FilmDetail;
  const title = S.common.titleWithYear(film.title, film.year);
  return file?.part_number != null ? S.player.titleWithPart(title, file.part_number) : title;
}

export function PlayerPage({ type, id }: PlayerPageProps) {
  const { rootEl, videoEl: video } = usePlayerShell();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const [search] = useSearchParams();
  const goBack = useGoBack('/');
  const profile = useCurrentProfile();
  const { crossTabNotice, dismissCrossTabNotice } = useAuth();
  const platform = getPlatform();
  const caps = getClientCaps();

  // `?file=` and the one-shot `?t=` as they were at mount.
  const [params] = useState(() => ({ file: parseFile(search.get('file')), t: parseStart(search.get('t')) }));

  // --- 1. item + start decision --------------------------------------------------------------
  // Rev 5: requested on every mount, even when a fresh copy is cached.
  const query = useQuery<PlayerItem>({
    queryKey: type === 'film' ? queryKeys.film(id) : queryKeys.episode(id),
    queryFn: ({ signal }) => (type === 'film' ? getFilm(id, signal) : getEpisode(id, signal)),
    staleTime: type === 'film' ? staleTimes.film : staleTimes.episode,
    refetchOnMount: 'always',
  });
  const decision = useStartDecision({ type, id, query, fileParam: params.file, tParam: params.t });
  const ready = decision !== null && decision !== 'no_file' ? decision : null;

  const [state, dispatch] = useReducer(playerReducer, undefined, initialPlayerState);
  const tracker = useRef(new PositionTracker()).current;
  const [appUpdated, setAppUpdated] = useState(false);

  const source: DecisionSource | undefined = query.data ?? ready?.source;
  const files = query.data?.media_files ?? ready?.files ?? [];
  const currentFile = files.find((f) => f.id === state.fileId) ?? ready?.file ?? null;
  const item = useMemo(() => playedItemOf(type, id, source), [type, id, source]);

  const loaded = useRef(false);
  useEffect(() => {
    if (!ready || loaded.current) return;
    loaded.current = true;
    tracker.position = ready.start;
    dispatch({ type: 'LOAD', fileId: ready.file.id, startPositionSeconds: ready.start });
    // `?t=` is one-shot (Rev 7/8): drop only `t`, keep everything else (never add `file`).
    if (search.has('t')) {
      const rest = new URLSearchParams(search);
      rest.delete('t');
      const query = rest.toString();
      navigate({ pathname: location.pathname, search: query ? `?${query}` : '' }, { replace: true });
    }
  }, [ready, tracker, search, navigate, location.pathname]);

  // Item 404 → `gone` (whichever arrives first), and Home drops the stale card.
  const itemGone = isApiError(query.error) && query.error.status === 404;
  useEffect(() => {
    if (itemGone) dispatch({ type: 'ITEM_NOT_FOUND' });
  }, [itemGone]);
  const isGone = state.error?.kind === 'gone';
  useEffect(() => {
    if (isGone) void queryClient.invalidateQueries({ queryKey: queryKeys.home() });
  }, [isGone, queryClient]);

  // --- 2. reporter BEFORE session (its final report runs before the element is released) -------
  const reporter = useProgressReporter({ item, fileId: state.fileId, stateName: state.name, hasPlayed: state.hasPlayed, tracker });
  const session = usePlaybackSession({ state, dispatch, video, tracker, onAppUpdated: () => setAppUpdated(true) });

  // --- toasts ------------------------------------------------------------------------------
  const toasts = usePlayerToasts();
  const notify = toasts.show;
  // Machine notices (audio switch failures) toast once per `seq`.
  const shownNotice = useRef<number | null>(null);
  useEffect(() => {
    const notice = state.notice;
    if (!notice || shownNotice.current === notice.seq) return;
    shownNotice.current = notice.seq;
    notify(notice.kind === 'audio_unsupported' ? S.player.audioUnsupported : notice.message);
  }, [state.notice, notify]);

  // D11: "Reproduciendo otra versión desde el principio" for 4 s once playback starts.
  const differentVersionShown = useRef(false);
  useEffect(() => {
    if (!state.hasPlayed || !ready?.differentVersion || differentVersionShown.current) return;
    differentVersionShown.current = true;
    notify(S.player.differentVersion);
  }, [state.hasPlayed, ready, notify]);

  // --- subtitles / audio --------------------------------------------------------------------
  const audioTracks = currentFile?.audio_tracks ?? [];
  const audioTrack = audioTracks.find((t) => t.index === state.audioStreamIndex) ?? null;
  const subtitles = usePlayerSubtitles({
    video,
    profileId: profile?.id ?? null,
    fileId: state.fileId,
    tracks: state.subtitleTracks,
    attachSeq: state.attachSeq,
    audioTrack,
    notify,
  });
  const [menu, setMenu] = useState<Menu>(null);
  const audioEnabled = canSwitchAudio(state.name);

  // --- clock, volume, fullscreen --------------------------------------------------------------
  // The time display and seek bar read the element clock inside Controls; the page re-renders only
  // when the element's duration or the near-end flag changes, not on every timeupdate.
  const showTracked = state.sessionReleased || TRACKED_TIME_STATES.has(state.name);
  const elementDuration = useMediaSelector(video, mediaDuration);
  const duration = state.durationSeconds ?? elementDuration ?? currentFile?.duration_seconds ?? null;
  const withinLead = useMediaSelector(video, (v) => isWithinLead(Number.isFinite(v.currentTime) ? v.currentTime : 0, duration));
  const volume = useVolume(video);
  const fullscreen = useFullscreen(rootEl, video);

  // --- next episode / next part ---------------------------------------------------------------
  const finalThenNavigate = useCallback(
    (event: 'NEXT' | 'NEXT_PART', to: string) => {
      reporter.sendFinal();
      dispatch({ type: event });
      navigate(to, { replace: true });
    },
    [reporter, navigate],
  );
  const playNextEpisode = useCallback(
    (next: EpisodeDetail) => finalThenNavigate('NEXT', playerUrl('episode', next.id)),
    [finalThenNavigate],
  );
  const nextUp = useNextUp({
    episode: type === 'episode' ? (query.data as EpisodeDetail | undefined) : undefined,
    isEpisode: type === 'episode',
    files,
    currentFile,
    stateName: state.name,
    hasPlayed: state.hasPlayed,
    withinLead,
    onNextEpisode: playNextEpisode,
  });
  const nextPartFile = nextUp.nextPartFile;
  const playNextPart = useCallback(() => {
    if (nextPartFile) finalThenNavigate('NEXT_PART', playerUrl('film', id, { file: nextPartFile.id, t: 0 }));
  }, [nextPartFile, finalThenNavigate, id]);
  const goNext = nextUp.nextEpisode ? () => playNextEpisode(nextUp.nextEpisode as EpisodeDetail) : nextPartFile ? playNextPart : undefined;

  // Cancel of the initial preparing wait → back, as Back does.
  const cancelled = state.name === 'exited' && state.exitReason === 'cancel';
  useEffect(() => {
    if (cancelled) goBack();
  }, [cancelled, goBack]);

  // --- actions ------------------------------------------------------------------------------
  const playing = state.name === 'playing' || state.name === 'buffering' || (state.name === 'loading' && !video.paused);
  const togglePlay = useCallback(() => (video.paused ? session.play() : session.pause()), [video, session]);
  const seekTo = useCallback(
    (seconds: number) => {
      if (state.sessionReleased || (!video.hasAttribute('src') && state.mode !== 'hls')) return;
      const end = duration ?? Number.POSITIVE_INFINITY;
      video.currentTime = Math.min(end, Math.max(0, seconds));
    },
    [video, duration, state.sessionReleased, state.mode],
  );
  const seekBy = useCallback((delta: number) => seekTo(video.currentTime + delta), [seekTo, video]);
  const alternatives = currentFile ? playableAlternatives(files, currentFile, caps, state.failedFileIds) : [];
  const tryFile = (file: MediaFile) => session.playFile(file.id, startPosition(file, files, ready?.progress ?? null).seconds);

  const screen = SCREEN_STATES.has(state.name) || appUpdated;
  const hideControls = useAutoHide(state.name === 'playing' && menu === null);

  useKeyboardShortcuts(
    {
      togglePlay,
      seekBy,
      changeVolume: volume.changeVolume,
      toggleFullscreen: fullscreen.toggle,
      toggleMute: volume.toggleMute,
      closeMenu: () => setMenu(null),
      next: goNext,
      activateBlocked: session.play,
    },
    {
      enabled: !screen && state.name !== 'preparing',
      menuOpen: menu !== null,
      blocked: state.name === 'blocked',
      volumeEnabled: !platform.ios,
      nextAvailable: goNext !== undefined,
    },
  );

  // Touch: a tap toggles the controls, a double tap on the left/right third seeks ∓10 s.
  const lastTap = useRef<{ at: number; zone: number } | null>(null);
  const onSurfacePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType !== 'touch') return;
    const rect = event.currentTarget.getBoundingClientRect();
    const zone = rect.width > 0 ? Math.floor(((event.clientX - rect.left) / rect.width) * 3) : 1;
    const now = Date.now();
    const previous = lastTap.current;
    if (previous && now - previous.at < DOUBLE_TAP_MS && previous.zone === zone && zone !== 1) {
      lastTap.current = null;
      seekBy(zone === 0 ? -10 : 10);
      return;
    }
    lastTap.current = { at: now, zone };
    hideControls.toggle();
  };

  useDocumentTitle(source ? playerTitle(type, source, currentFile) : null);

  // --- render --------------------------------------------------------------------------------
  const title = playerTitle(type, source, currentFile);
  const loadFailed = decision === null && query.isError && !itemGone && state.name === 'idle';
  const crossTabToast: PlayerToast[] = crossTabNotice
    ? [
        {
          id: -1,
          message: S.crossTab.banner(crossTabNotice.name, crossTabNotice.current),
          action: { label: S.crossTab.reload, onClick: () => reloader.reload() },
          onDismiss: dismissCrossTabNotice,
        },
      ]
    : [];
  const endedWithoutPrompt =
    state.name === 'ended' && !nextUp.nextEpisode && !nextPartFile && (nextUp.noNextEpisode || query.isError);

  return (
    <div
      className={styles.page}
      onPointerMove={(event) => event.pointerType !== 'touch' && hideControls.poke()}
    >
      <div className={styles.surface} onPointerUp={onSurfacePointerUp} data-testid="player-surface" />
      {subtitles.trackElement}

      {SPINNER_STATES.has(state.name) && !loadFailed && decision !== 'no_file' && !appUpdated && (
        <div className={styles.overlay}>
          <Spinner label={S.player.loading} />
        </div>
      )}

      <Controls
        visible={hideControls.visible || screen}
        title={title}
        watched={reporter.markedWatched}
        onBack={goBack}
        showBottom={!screen && ready !== null}
        playing={playing}
        onPlayPause={togglePlay}
        onSeekBy={seekBy}
        video={video}
        trackedPosition={showTracked ? () => tracker.position : null}
        duration={duration}
        onSeek={seekTo}
        volume={volume.volume}
        muted={volume.muted}
        volumeSlider={!platform.ios}
        onVolume={volume.setVolume}
        onToggleMute={volume.toggleMute}
        subtitleMenu={
          <SubtitleMenu
            tracks={state.subtitleTracks}
            selected={subtitles.selected}
            pending={subtitles.pending}
            open={menu === 'subtitles'}
            onToggle={() => setMenu((m) => (m === 'subtitles' ? null : 'subtitles'))}
            onClose={() => setMenu(null)}
            onSelect={subtitles.choose}
          />
        }
        audioMenu={
          <AudioMenu
            tracks={audioTracks}
            current={state.audioStreamIndex}
            disabled={!audioEnabled}
            open={menu === 'audio'}
            onToggle={() => setMenu((m) => (m === 'audio' ? null : 'audio'))}
            onClose={() => setMenu(null)}
            onSelect={session.switchAudio}
          />
        }
        fullscreen={{ available: fullscreen.available, active: fullscreen.active, onToggle: fullscreen.toggle }}
      />

      {state.name === 'blocked' && <TapToPlayOverlay onPlay={session.play} />}

      {state.name === 'preparing' && (
        <PreparingOverlay
          estimateSeconds={state.prepareEstimateS}
          startedAt={state.prepareStartedAt}
          audioSwitch={state.pendingRequest?.origin === 'switching'}
          onCancel={session.cancel}
        />
      )}

      {nextUp.nextEpisode && (
        <NextEpisodePrompt
          episode={nextUp.nextEpisode}
          secondsLeft={nextUp.secondsLeft}
          onPlayNow={() => playNextEpisode(nextUp.nextEpisode as EpisodeDetail)}
          onCancel={nextUp.cancelNextEpisode}
        />
      )}

      {nextPartFile && currentFile?.part_number != null && nextPartFile.part_number != null && (
        <NextPartPrompt finishedPart={currentFile.part_number} nextPart={nextPartFile.part_number} onPlay={playNextPart} onBack={goBack} />
      )}

      {endedWithoutPrompt && (
        <div className={styles.prompt} role="dialog" aria-label={S.player.ended.label}>
          <div className={styles.promptActions}>
            <button type="button" className="button button-primary" onClick={session.play}>
              {S.player.ended.replay}
            </button>
            <button type="button" className="button" onClick={goBack}>
              {S.player.ended.back}
            </button>
          </div>
        </div>
      )}

      {state.name === 'unsupported' && state.unsupported && (
        <UnsupportedMessage
          reason={state.unsupported.reason}
          detail={state.unsupported.detail}
          codec={currentFile?.video?.codec ?? null}
          alternatives={alternatives}
          onTry={tryFile}
          onBack={goBack}
        />
      )}

      {state.name === 'error' && state.error && (
        <PlayerErrorScreen
          error={state.error}
          itemType={type}
          alternatives={alternatives}
          onRetry={session.retry}
          onTry={tryFile}
          onBack={goBack}
        />
      )}

      {decision === 'no_file' && (
        <div className={styles.screen}>
          <ErrorState message={S.film.noPlayableFile}>
            <button type="button" className="button" onClick={goBack}>
              {S.common.back}
            </button>
          </ErrorState>
        </div>
      )}

      {loadFailed && (
        <div className={styles.screen}>
          <ErrorState message={errorMessage(query.error)} onRetry={() => void query.refetch()}>
            <button type="button" className="button" onClick={goBack}>
              {S.common.back}
            </button>
          </ErrorState>
        </div>
      )}

      {appUpdated && (
        <div className={styles.screen}>
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
        </div>
      )}

      <PlayerToasts
        toasts={[...crossTabToast, ...toasts.toasts]}
        onDismiss={(toast) => (toast.onDismiss ? toast.onDismiss() : toasts.dismiss(toast.id))}
      />
    </div>
  );
}
