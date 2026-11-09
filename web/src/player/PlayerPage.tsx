// One playback of one item/file. Keyed by PlayerRoute on pathname + `?file=`, so the
// next episode / next part / an explicit `?file=` change remount it with a fresh machine,
// session, reporter and subtitle cache, while the shell's <video> and fullscreen root survive.
// Wiring (order matters: unmount cleanups run in declaration order):
//   start decision → LOAD → useProgressReporter → usePlaybackSession → UI.
// Rating prompt: a `rating_prompt` from a report shows nothing during playback;
// at `ended` with no next item the inline RatingForm replaces "Fin de la reproducción" until it is
// answered. Leaving with the form visible = Ahora no; leaving without it offers the prompt to the
// host (RatingPromptHost), which shows it over the screen returned to.
// Remembered tracks: LOAD carries the remembered audio matched on the start file (effective choice =
// session override ?? the start source's track_choice); later uses (another version, a later file's
// subtitle decision) read the live choice (`saver.base()`). Manual picks save through
// useTrackChoiceSaver. "Formato de los subtítulos" opens SubtitleStylePanel (`menu = 'style'`).
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type PointerEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { errorMessage, isApiError } from '@/api/errors';
import { getEpisode, getFilm } from '@/api/catalog';
import { mutationKeys, queryKeys, staleTimes } from '@/api/queryKeys';
import type { AudioTrack, EpisodeDetail, FilmDetail, HomeEpisode, ItemType, MediaFile, SubtitleChoice, TitleKind, TrackChoice } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { ErrorState } from '@/components/ErrorState';
import { Spinner } from '@/components/Spinner';
import { RatingForm } from '@/features/ratings/RatingDialog';
import { useRatingPrompt } from '@/features/ratings/ratingPrompt';
import { dismissInBackground } from '@/features/ratings/useRatingWrite';
import { clearChunkReloadMarker } from '@/lib/chunkReload';
import { cx } from '@/lib/cx';
import { episodeLabel } from '@/lib/episodes';
import { reloader } from '@/lib/reload';
import { removeKey } from '@/lib/storage';
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
import { SubtitleStylePanel } from './SubtitleStylePanel';
import { TapToPlayOverlay } from './TapToPlayOverlay';
import { audioChoiceOf, defaultAudio, matchAudio, sameAudioChoice } from './trackChoice';
import { pendingChoice } from './trackChoiceSession';
import { AudioMenu, SubtitleMenu } from './TrackMenu';
import { UnsupportedMessage } from './UnsupportedMessage';
import { useAutoHide } from './useAutoHide';
import { useFullscreen } from './useFullscreen';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { mediaSessionInfo, useMediaSession } from './useMediaSession';
import { mediaDuration, useMediaPaused, useMediaSelector } from './useMediaClock';
import { isWithinLead, useNextUp } from './useNextUp';
import { usePlaybackSession } from './usePlaybackSession';
import { usePlayerSubtitles } from './usePlayerSubtitles';
import { PositionTracker, useProgressReporter } from './useProgressReporter';
import { playedItemOf, useStartDecision, type DecisionSource, type PlayerItem } from './useStartDecision';
import { useSubtitleStyleSave } from './useSubtitleStyleSave';
import { useTrackChoiceSaver } from './useTrackChoiceSaver';
import { useVolume } from './useVolume';
import { playableAlternatives, startPosition } from './versions';

export interface PlayerPageProps {
  type: ItemType;
  id: number;
}

type Menu = 'subtitles' | 'audio' | 'style' | null;

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

/** The show of an episode source (the title whose choice applies). */
const showIdOf = (source: DecisionSource | undefined) => (source as EpisodeDetail | HomeEpisode | undefined)?.show_id ?? null;

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
  const { crossTabNotice, dismissCrossTabNotice, session: authSession } = useAuth();
  const platform = getPlatform();
  const caps = getClientCaps();

  // `?file=` and the one-shot `?t=` as they were at mount.
  const [params] = useState(() => ({ file: parseFile(search.get('file')), t: parseStart(search.get('t')) }));

  // --- 1. item + start decision --------------------------------------------------------------
  // Requested on every mount, even when a fresh copy is cached.
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
  /** The inline rating form was answered (saved, Ahora no, 404): the end dialog shows. */
  const [ratingHandled, setRatingHandled] = useState(false);

  const source: DecisionSource | undefined = query.data ?? ready?.source;
  const files = query.data?.media_files ?? ready?.files ?? [];
  const currentFile = files.find((f) => f.id === state.fileId) ?? ready?.file ?? null;
  const item = useMemo(() => playedItemOf(type, id, source), [type, id, source]);

  // --- remembered audio/subtitle choice ------------------------------------------------------
  const profileId = authSession?.profile_id ?? null;
  const titleKind: TitleKind = type === 'film' ? 'film' : 'show';
  const saver = useTrackChoiceSaver({ kind: titleKind, id: type === 'film' ? id : showIdOf(source), profileId, source });
  /** The start's effective choice (first file); after a PLAY_FILE the live one (saver.base()). */
  const startChoice = useRef<TrackChoice | null>(null);
  const playFileUsed = useRef(false);

  // The legacy per-browser subtitle language is gone (the server remembers picks now).
  const legacyRemoved = useRef(false);
  useEffect(() => {
    if (profileId === null || legacyRemoved.current) return;
    legacyRemoved.current = true;
    removeKey(`hometv.subtitleLang.${profileId}`);
  }, [profileId]);

  const loaded = useRef(false);
  const loadInput = useRef({ profileId, titleKind });
  loadInput.current = { profileId, titleKind };
  useEffect(() => {
    if (!ready || loaded.current) return;
    loaded.current = true;
    tracker.position = ready.start;
    const { profileId: pid, titleKind: kind } = loadInput.current;
    const titleId = type === 'film' ? id : showIdOf(ready.source);
    const pending = pid !== null && titleId !== null ? pendingChoice(pid, kind, titleId) : undefined;
    const effective = pending ?? ready.source.track_choice ?? null;
    startChoice.current = effective;
    const audioStreamIndex = matchAudio(effective?.audio ?? null, ready.file.audio_tracks);
    dispatch({
      type: 'LOAD',
      fileId: ready.file.id,
      startPositionSeconds: ready.start,
      audioStreamIndex,
      audioFromChoice: audioStreamIndex !== null,
    });
    // `?t=` is one-shot: drop only `t`, keep everything else (never add `file`).
    if (search.has('t')) {
      const rest = new URLSearchParams(search);
      rest.delete('t');
      const query = rest.toString();
      navigate({ pathname: location.pathname, search: query ? `?${query}` : '' }, { replace: true });
    }
  }, [ready, tracker, search, navigate, location.pathname, type, id]);

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
  const { offer, markHandled } = useRatingPrompt();
  const reporter = useProgressReporter({
    item,
    fileId: state.fileId,
    stateName: state.name,
    hasPlayed: state.hasPlayed,
    tracker,
    onFinalPrompt: offer,
  });
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

  // Different version: "Reproduciendo otra versión desde el principio" for 4 s once playback starts.
  const differentVersionShown = useRef(false);
  useEffect(() => {
    if (!state.hasPlayed || !ready?.differentVersion || differentVersionShown.current) return;
    differentVersionShown.current = true;
    notify(S.player.differentVersion);
  }, [state.hasPlayed, ready, notify]);

  // --- subtitles / audio --------------------------------------------------------------------
  const audioTracks = currentFile?.audio_tracks ?? [];
  // The track the decision answered with, else the file default (forced-subtitle default rule).
  const audioTrack: AudioTrack | null = audioTracks.find((t) => t.index === state.audioStreamIndex) ?? defaultAudio(audioTracks);
  const { saveSubtitles } = saver;
  const onSubtitlePick = useCallback((part: SubtitleChoice) => saveSubtitles(part), [saveSubtitles]);
  const subtitles = usePlayerSubtitles({
    video,
    choice: playFileUsed.current ? saver.base() : startChoice.current,
    fileId: state.fileId,
    tracks: state.subtitleTracks,
    attachSeq: state.attachSeq,
    audioTrack,
    notify,
    onPick: onSubtitlePick,
  });
  const [menu, setMenu] = useState<Menu>(null);
  const audioEnabled = canSwitchAudio(state.name);
  const subtitleButtonRef = useRef<HTMLButtonElement>(null);
  const saveStyle = useSubtitleStyleSave(notify);
  // The style panel closed (Cerrar / Escape): focus back on the "Subtítulos" button.
  const previousMenu = useRef<Menu>(null);
  useEffect(() => {
    if (previousMenu.current === 'style' && menu === null) subtitleButtonRef.current?.focus();
    previousMenu.current = menu;
  }, [menu]);

  /** Every audio item: a switch when it differs; a re-pick saves only when it changes the held part. */
  const selectAudio = (index: number) => {
    const track = audioTracks.find((t) => t.index === index);
    if (!track) return;
    const pick = audioChoiceOf(track);
    if (index !== state.audioStreamIndex) {
      saver.saveAudio(pick);
      session.switchAudio(index);
    } else if (!sameAudioChoice(pick, saver.base()?.audio)) {
      saver.saveAudio(pick);
    }
  };

  // --- clock, volume, fullscreen --------------------------------------------------------------
  // The time display and seek bar read the element clock inside Controls; the page re-renders only
  // when the element's duration or the near-end flag changes, not on every timeupdate.
  const showTracked = state.sessionReleased || TRACKED_TIME_STATES.has(state.name);
  const elementDuration = useMediaSelector(video, mediaDuration);
  const duration = state.durationSeconds ?? elementDuration ?? currentFile?.duration_seconds ?? null;
  const withinLead = useMediaSelector(video, (v) => isWithinLead(Number.isFinite(v.currentTime) ? v.currentTime : 0, duration));
  const elementPaused = useMediaPaused(video);
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
  const playing = state.name === 'playing' || state.name === 'buffering' || (state.name === 'loading' && !elementPaused);
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
  // Another version: the remembered audio matched on that file from the live choice.
  const tryFile = (file: MediaFile) => {
    playFileUsed.current = true;
    const audio = matchAudio(saver.base()?.audio ?? null, file.audio_tracks);
    session.playFile(file.id, startPosition(file, files, ready?.progress ?? null).seconds, audio);
  };

  const screen = SCREEN_STATES.has(state.name) || appUpdated;
  const hideControls = useAutoHide(state.name === 'playing' && menu === null);
  // The end state waits for next-episode resolution (no flash of either block while loading).
  const endedAtLastItem =
    state.name === 'ended' && !nextUp.nextEpisode && !nextPartFile && (nextUp.noNextEpisode || query.isError);
  const endedWithRating = endedAtLastItem && reporter.ratingPrompt !== null && !ratingHandled;
  const endedWithoutPrompt = endedAtLastItem && !endedWithRating;
  // Player keys and media-session handlers are off while the rating form shows.
  const shortcutsEnabled = !screen && state.name !== 'preparing' && !endedWithRating;
  const title = playerTitle(type, source, currentFile);

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
      play: session.play,
      pause: session.pause,
    },
    {
      enabled: shortcutsEnabled,
      menuOpen: menu !== null,
      blocked: state.name === 'blocked',
      volumeEnabled: !platform.ios,
      nextAvailable: goNext !== undefined,
      // With the Media Session API its handlers (useMediaSession) are the only media-key path.
      mediaKeys: !('mediaSession' in navigator),
    },
  );

  // Headset / Bluetooth / OS media keys and Now Playing: the same play, pause and ∓10 s paths.
  const nowPlaying = useMemo(() => mediaSessionInfo(type, source, title), [type, source, title]);
  useMediaSession({
    // Gone (item or file 404): no Now Playing card for something that is no longer there.
    info: isGone ? null : nowPlaying,
    playing,
    enabled: shortcutsEnabled,
    onPlay: session.play,
    onPause: session.pause,
    onSeekBy: seekBy,
  });

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

  useDocumentTitle(source ? title : null);

  // Unmount only (no deps; the cleanup reads a ref, never render values): leaving with the rating
  // form visible = Ahora no, unless its save is in flight (the PUT decides); leaving without it
  // offers the prompt (a no-op for a title already answered: every answer marks it handled).
  const exit = { formVisible: endedWithRating, prompt: reporter.ratingPrompt, offer, markHandled, queryClient };
  const exitState = useRef(exit);
  exitState.current = exit;
  useEffect(
    () => () => {
      const { formVisible, prompt, offer, markHandled, queryClient } = exitState.current;
      if (prompt === null) return;
      if (formVisible) {
        markHandled(prompt.kind, prompt.id);
        const saving = queryClient.isMutating({ mutationKey: mutationKeys.ratingWrite(prompt.kind, prompt.id) }) > 0;
        if (!saving) dismissInBackground(prompt.kind, prompt.id);
      } else {
        offer(prompt);
      }
    },
    [],
  );

  // --- render --------------------------------------------------------------------------------
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
            onFormat={() => setMenu('style')}
            buttonRef={subtitleButtonRef}
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
            onSelect={selectAudio}
          />
        }
        fullscreen={{ available: fullscreen.available, active: fullscreen.active, onToggle: fullscreen.toggle }}
      />

      {menu === 'style' && (
        <SubtitleStylePanel onClose={() => setMenu(null)} onSave={saveStyle} shownTrack={subtitles.shownTrack} />
      )}

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

      {endedWithRating && reporter.ratingPrompt && (
        <div className={cx(styles.prompt, styles.ratingPrompt)} role="dialog" aria-label={S.ratings.question}>
          <RatingForm
            mode="prompt"
            inline
            target={reporter.ratingPrompt}
            initial={null}
            notify={notify}
            onAnswered={() => setRatingHandled(true)}
          />
        </div>
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
