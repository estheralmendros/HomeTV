// Subtitle selection and loading for one PlayerPage mount:
// - one SubtitleLoader per mount (blob URLs cached by `${fileId}:${track.id}`, revoked when the
//   file changes and on unmount);
// - at the first decision of each file the remembered choice is matched (`matchSubtitle`: the
//   `getChoice()` answer at that moment, today's default rule without a subtitle part) and loads in
//   parallel with the video; the same file keeps its selection on later decisions;
// - the selected, loaded track is rendered as a blob-URL <track> portaled into the shell's
//   <video> and set to `showing`; pending tracks show "Preparando…"; failures toast and revert
//   to Off (automatic reverts never save);
// - auto cues are lifted to the line of the effective "Posición" (the panel draft's, else the
//   saved style's), re-lifted live when it changes; the hook subscribes to that string only;
// - a menu pick (incl. "Desactivados") calls `onPick` with the built subtitle part.
import { useCallback, useContext, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import type { AudioTrack, SubtitleChoice, SubtitleTrack, TrackChoice } from '@/api/types';
import { useMeQuery } from '@/auth/useCurrentProfile';
import { toBcp47 } from '@/lib/languages';
import { S } from '@/strings.es';
import { PlayerShellContext } from './PlayerRoute';
import { SubtitleLoader } from './subtitleLoader';
import { cueLine, subtitleStyleOf } from './subtitleStyle';
import { matchSubtitle, subtitleChoiceOf } from './trackChoice';
import { SUBTITLES_OFF, liftCues, subtitleLabel } from './tracks';

export interface PlayerSubtitlesInput {
  video: HTMLVideoElement;
  /** The effective track choice for a new file's first decision (called only when it happens). */
  getChoice: () => TrackChoice | null;
  fileId: number | null;
  /** The playback response's tracks (URLs come from here). */
  tracks: readonly SubtitleTrack[];
  /** Incremented by every decision (machine `attachSeq`). */
  attachSeq: number;
  /** The audio track the decision answered with (else the file default). */
  audioTrack: AudioTrack | null;
  notify: (message: string) => void;
  /** A manual pick in the menu (never the automatic reverts). */
  onPick?: (part: SubtitleChoice) => void;
}

const noSubscription = () => () => undefined;

export function usePlayerSubtitles({ video, getChoice, fileId, tracks, attachSeq, audioTrack, notify, onPick }: PlayerSubtitlesInput) {
  const loaderRef = useRef<SubtitleLoader | null>(null);
  const [selected, setSelected] = useState<string>(SUBTITLES_OFF);
  const [loaded, setLoaded] = useState<{ trackId: string; url: string } | null>(null);
  const [pending, setPending] = useState<ReadonlySet<string>>(() => new Set());
  /** The shown TextTrack (the style panel's sample line follows its active cues). */
  const [shownTrack, setShownTrack] = useState<TextTrack | null>(null);
  const defaultFor = useRef<number | null>(null);
  const trackRef = useRef<HTMLTrackElement | null>(null);
  // React clears a ref before it takes the node out of the DOM: our <track> is disabled first (Off,
  // another track, another file, unmount). Firefox keeps painting the active cue of a showing <track>
  // removed from the <video>, which froze the last line on screen after "Desactivados".
  const setTrackEl = useCallback((el: HTMLTrackElement | null) => {
    const leaving = trackRef.current;
    if (!el && leaving) leaving.track.mode = 'disabled';
    trackRef.current = el;
  }, []);
  const latest = useRef({ notify, tracks, audioTrack, getChoice, onPick });
  latest.current = { notify, tracks, audioTrack, getChoice, onPick };

  // The effective position only: a size/colour/edge step of the panel never re-renders the page.
  const draft = useContext(PlayerShellContext)?.subtitleStyleDraft;
  const savedPosition = subtitleStyleOf(useMeQuery().data).position;
  const position = useSyncExternalStore(draft?.subscribe ?? noSubscription, () => draft?.get()?.position ?? savedPosition);
  const line = cueLine(position);

  // One loader per mount (created in an effect so StrictMode's simulated unmount can't kill it).
  useEffect(() => {
    const loader = new SubtitleLoader();
    loaderRef.current = loader;
    return () => {
      loader.dispose();
      if (loaderRef.current === loader) loaderRef.current = null;
    };
  }, []);

  // A new file (PLAY_FILE): forget the other files' blob URLs.
  useEffect(() => {
    if (fileId != null) loaderRef.current?.setFile(fileId);
  }, [fileId]);

  // The remembered (else default) track, at the first decision of each file. A PLAY_FILE sets the
  // new file id before its decision: wait for the next attach (the previous file's tracks are gone).
  const awaitingDecision = useRef<{ fileId: number; seq: number } | null>(null);
  useEffect(() => {
    if (attachSeq === 0 || fileId == null || defaultFor.current === fileId) return;
    if (defaultFor.current !== null) {
      if (awaitingDecision.current?.fileId !== fileId) awaitingDecision.current = { fileId, seq: attachSeq };
      if (attachSeq === awaitingDecision.current.seq) return;
    }
    defaultFor.current = fileId;
    const { getChoice: choiceNow, tracks: current, audioTrack: audio } = latest.current;
    const effective = choiceNow();
    setSelected(matchSubtitle(effective?.subtitles ?? null, current, audio?.language ?? null) ?? SUBTITLES_OFF);
  }, [attachSeq, fileId]);

  const track = selected === SUBTITLES_OFF ? null : (tracks.find((t) => t.id === selected && t.kind === 'text') ?? null);
  const trackUrl = track?.url ?? null;

  // Load the selected track (never awaited before playback).
  useEffect(() => {
    const loader = loaderRef.current;
    const current = latest.current.tracks.find((t) => t.id === selected && t.kind === 'text');
    if (!loader || fileId == null || !current || !trackUrl) {
      setLoaded(null);
      return;
    }
    const cached = loader.cached(fileId, current.id);
    if (cached) {
      setLoaded({ trackId: current.id, url: cached });
      return;
    }
    setLoaded(null);
    const controller = new AbortController();
    const clearPending = () =>
      setPending((set) => {
        if (!set.has(current.id)) return set;
        const next = new Set(set);
        next.delete(current.id);
        return next;
      });
    void loader
      .load(fileId, current, {
        signal: controller.signal,
        onPending: () => setPending((set) => (set.has(current.id) ? set : new Set(set).add(current.id))),
      })
      .then((result) => {
        if (controller.signal.aborted || result.status === 'aborted') return;
        clearPending();
        if (result.status === 'loaded') {
          setLoaded({ trackId: current.id, url: result.url });
        } else {
          latest.current.notify(result.message);
          setSelected(SUBTITLES_OFF);
        }
      });
    return () => {
      controller.abort();
      clearPending();
    };
  }, [selected, fileId, trackUrl]);

  const blobUrl = loaded && loaded.trackId === selected ? loaded.url : null;

  // Show our <track>, disable every other text track of the element.
  useEffect(() => {
    const ours = blobUrl ? trackRef.current?.track : undefined;
    if (ours) ours.mode = 'showing';
    setShownTrack(ours ?? null);
    const all = video.textTracks as TextTrackList | undefined;
    if (all) {
      for (let i = 0; i < all.length; i += 1) {
        const textTrack = all[i];
        if (textTrack && textTrack !== ours) textTrack.mode = 'disabled';
      }
    }
  }, [blobUrl, video]);

  // A malformed VTT toasts and reverts to Off.
  useEffect(() => {
    const el = trackRef.current;
    if (!blobUrl || !el) return;
    const onError = () => {
      latest.current.notify(S.player.subtitles.loadFailed);
      setSelected(SUBTITLES_OFF);
    };
    el.addEventListener('error', onError);
    return () => el.removeEventListener('error', onError);
  }, [blobUrl]);

  // Loaded cues are lifted above the control bar to the position's line (one WeakSet per loaded
  // track: only the cues this hook moved are moved again when "Posición" changes).
  const lifted = useRef<{ url: string; cues: WeakSet<object> } | null>(null);
  useEffect(() => {
    const el = trackRef.current;
    if (!blobUrl || !el) return;
    if (lifted.current?.url !== blobUrl) lifted.current = { url: blobUrl, cues: new WeakSet() };
    const set = lifted.current.cues;
    const onLoad = () => liftCues(el.track.cues as ArrayLike<VTTCue> | null, line, set);
    onLoad();
    el.addEventListener('load', onLoad);
    return () => el.removeEventListener('load', onLoad);
  }, [blobUrl, line]);

  const choose = useCallback((trackId: string) => {
    setSelected(trackId);
    const { tracks: current, onPick: pick } = latest.current;
    const chosen = trackId === SUBTITLES_OFF ? null : (current.find((t) => t.id === trackId) ?? null);
    pick?.(subtitleChoiceOf(chosen));
  }, []);

  const trackElement =
    blobUrl && track
      ? createPortal(
          <track
            key={blobUrl}
            ref={setTrackEl}
            kind="subtitles"
            src={blobUrl}
            srcLang={toBcp47(track.language) ?? undefined}
            label={subtitleLabel(track)}
          />,
          video,
        )
      : null;

  return { selected, pending, choose, trackElement, shownTrack };
}
