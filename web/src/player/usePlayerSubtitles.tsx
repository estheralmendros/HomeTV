// Subtitle selection and loading for one PlayerPage mount (prd2 §9 steps 6–7):
// - one SubtitleLoader per mount (blob URLs cached by `${fileId}:${track.id}`, revoked when the
//   file changes and on unmount);
// - the default track (stored language → default flag → forced in the audio language → off) is
//   chosen at the first decision of each file and loads in parallel with the video;
// - the selected, loaded track is rendered as a blob-URL <track> portaled into the shell's
//   <video> and set to `showing`; pending tracks show "Preparando…"; failures toast and revert
//   to Off; a choice is saved to `hometv.subtitleLang.<pid>`.
import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { AudioTrack, SubtitleTrack } from '@/api/types';
import { toBcp47 } from '@/lib/languages';
import { S } from '@/strings.es';
import { SubtitleLoader } from './subtitleLoader';
import { SUBTITLES_OFF, defaultSubtitle, liftCues, readSubtitleLang, subtitleLabel, writeSubtitleLang } from './tracks';

export interface PlayerSubtitlesInput {
  video: HTMLVideoElement;
  profileId: number | null;
  fileId: number | null;
  /** The playback response's tracks (URLs come from here). */
  tracks: readonly SubtitleTrack[];
  /** Incremented by every decision (machine `attachSeq`). */
  attachSeq: number;
  /** The selected audio track (forced-subtitle default). */
  audioTrack: AudioTrack | null;
  notify: (message: string) => void;
}

export function usePlayerSubtitles({ video, profileId, fileId, tracks, attachSeq, audioTrack, notify }: PlayerSubtitlesInput) {
  const loaderRef = useRef<SubtitleLoader | null>(null);
  const [selected, setSelected] = useState<string>(SUBTITLES_OFF);
  const [loaded, setLoaded] = useState<{ trackId: string; url: string } | null>(null);
  const [pending, setPending] = useState<ReadonlySet<string>>(() => new Set());
  const defaultFor = useRef<number | null>(null);
  const trackRef = useRef<HTMLTrackElement | null>(null);
  const latest = useRef({ notify, tracks, audioTrack, profileId });
  latest.current = { notify, tracks, audioTrack, profileId };

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

  // The default track, at the first decision of each file.
  useEffect(() => {
    if (attachSeq === 0 || fileId == null || defaultFor.current === fileId) return;
    defaultFor.current = fileId;
    const { profileId: pid, tracks: current, audioTrack: audio } = latest.current;
    const storedLang = pid == null ? null : readSubtitleLang(pid);
    const track = defaultSubtitle(current, { storedLang, audioLanguage: audio?.language ?? null });
    setSelected(track?.id ?? SUBTITLES_OFF);
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
    const all = video.textTracks as TextTrackList | undefined;
    if (all) {
      for (let i = 0; i < all.length; i += 1) {
        const textTrack = all[i];
        if (textTrack && textTrack !== ours) textTrack.mode = 'disabled';
      }
    }
  }, [blobUrl, video]);

  // Loaded cues are lifted above the control bar; a malformed VTT toasts and reverts to Off.
  useEffect(() => {
    const el = trackRef.current;
    if (!blobUrl || !el) return;
    const onLoad = () => liftCues(el.track.cues as ArrayLike<VTTCue> | null);
    const onError = () => {
      latest.current.notify(S.player.subtitles.loadFailed);
      setSelected(SUBTITLES_OFF);
    };
    onLoad();
    el.addEventListener('load', onLoad);
    el.addEventListener('error', onError);
    return () => {
      el.removeEventListener('load', onLoad);
      el.removeEventListener('error', onError);
    };
  }, [blobUrl]);

  const choose = useCallback(
    (trackId: string) => {
      setSelected(trackId);
      const { profileId: pid, tracks: current } = latest.current;
      if (pid == null) return;
      const chosen = current.find((t) => t.id === trackId);
      writeSubtitleLang(pid, trackId === SUBTITLES_OFF || !chosen ? SUBTITLES_OFF : chosen.language);
    },
    [],
  );

  const trackElement =
    blobUrl && track
      ? createPortal(
          <track
            key={blobUrl}
            ref={trackRef}
            kind="subtitles"
            src={blobUrl}
            srcLang={toBcp47(track.language) ?? undefined}
            label={subtitleLabel(track)}
          />,
          video,
        )
      : null;

  return { selected, pending, choose, trackElement };
}
