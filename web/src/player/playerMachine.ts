// Player state machine: a pure reducer. Effects (usePlaybackSession,
// useProgressReporter, PlayerPage) dispatch events and act on the resulting state; everything
// time-dependent arrives in the event payload (`now`, `position`), so the reducer stays pure.
// Events not listed for a state return the SAME state object (explicit no-ops).
import { ApiError, errorMessage } from '@/api/errors';
import type { PlaybackDirect, PlaybackHls, PlaybackUnsupported, SubtitleTrack } from '@/api/types';
import { S } from '@/strings.es';

export const ALL_STATE_NAMES = [
  'idle',
  'requesting',
  'preparing',
  'loading',
  'blocked',
  'playing',
  'paused',
  'buffering',
  'switching',
  'recovering',
  'ended',
  'unsupported',
  'error',
  'exited',
] as const;
export type PlayerStateName = (typeof ALL_STATE_NAMES)[number];

/** States that send a playback request; `preparing` returns to its origin on each tick. */
export type RequestOrigin = 'requesting' | 'switching' | 'recovering';
export type PlaybackMode = 'direct' | 'hls';
export type PlayerErrorKind = 'busy' | 'gone' | 'prepare_timeout' | 'decode' | 'failed' | 'version_failed';

export interface PlayerError {
  kind: PlayerErrorKind;
  code: string;
  /** Catalog text (Spanish). */
  message: string;
  /** English diagnostic for "Detalles técnicos" (request-time transcoder_failed only). */
  detail: string | null;
}

/** A `POST playback` to send (client_caps are added by the effect). */
export interface PlaybackIntent {
  fileId: number;
  audioStreamIndex: number | null;
  startPositionSeconds: number;
  previousSessionId: string | null;
  /** The attach starts tracking from `startPositionSeconds` (PLAY_FILE, play again after a release). */
  resetPosition: boolean;
}

export interface PendingRequest {
  origin: RequestOrigin;
  intent: PlaybackIntent;
}

export type ResumeState = 'playing' | 'paused';

export interface SwitchFrom {
  t: number;
  prevAudio: number | null;
  /** The old HLS session was handed over as previous_session_id (the server stopped it). */
  oldSessionReleased: boolean;
}

export type PlayerNotice =
  | { kind: 'audio_unsupported'; seq: number }
  | { kind: 'switch_failed'; message: string; seq: number };

export type ExitReason = 'cancel' | 'next' | 'next_part';
export type MediaErrorSource = 'hls' | 'native' | 'direct';
export type MediaErrorKind = 'network' | 'decode' | 'gone';

export interface PlayerState {
  name: PlayerStateName;
  mode: PlaybackMode | null;
  fileId: number | null;
  sessionId: string | null;
  url: string | null;
  audioStreamIndex: number | null;
  durationSeconds: number | null;
  subtitleTracks: SubtitleTrack[];
  unsupported: { reason: string; detail: string } | null;
  /** start_position_seconds of the current attach (tracking goes live once it is reached). */
  attachStart: number;
  /** The current attach must reset the tracked position to `attachStart`. */
  attachResetsPosition: boolean;
  /** Incremented by every decision: the effect attaches a new source. */
  attachSeq: number;
  /** Incremented whenever a request must be (re)sent. */
  requestSeq: number;
  /** Incremented when the old source must resume in place (switch restored in direct mode). */
  resumeSeq: number;
  pendingRequest: PendingRequest | null;
  /** The last request sent (Retry re-sends it at the saved position). */
  lastIntent: PlaybackIntent | null;
  lastRecoveryAt: number | null;
  prepareStartedAt: number | null;
  prepareBudgetS: number | null;
  prepareEstimateS: number | null;
  prepareRetryDelayS: number | null;
  resumeState: ResumeState | null;
  switchFrom: SwitchFrom | null;
  sessionReleased: boolean;
  error: PlayerError | null;
  failedFileIds: readonly number[];
  hasPlayed: boolean;
  notice: PlayerNotice | null;
  exitReason: ExitReason | null;
}

export type PlayerEvent =
  | { type: 'LOAD'; fileId: number; startPositionSeconds: number; audioStreamIndex?: number | null }
  | { type: 'ITEM_NOT_FOUND' }
  | { type: 'DECISION_DIRECT'; response: PlaybackDirect }
  | { type: 'DECISION_HLS'; response: PlaybackHls }
  | { type: 'DECISION_UNSUPPORTED'; response: PlaybackUnsupported }
  | { type: 'PREPARING'; now: number; estimatedSeconds: number | null; retryAfterSeconds: number }
  | { type: 'PREPARE_TICK'; now: number }
  | { type: 'CANCEL' }
  | { type: 'REQUEST_FAILED'; error: PlayerError }
  | { type: 'CAN_PLAY' }
  | { type: 'PLAYING' }
  | { type: 'PAUSE' }
  /** The app's own play action; `position` = the saved position (lastKnownPosition). */
  | { type: 'PLAY'; position: number }
  | { type: 'PLAY_BLOCKED' }
  | { type: 'WAITING' }
  | { type: 'ENDED' }
  | { type: 'SWITCH_AUDIO'; audioStreamIndex: number; t: number }
  | { type: 'MEDIA_ERROR'; now: number; source: MediaErrorSource; kind: MediaErrorKind; position: number; code?: string }
  | { type: 'PAUSE_RELEASE' }
  | { type: 'RETRY'; position: number }
  | { type: 'PLAY_FILE'; fileId: number; startPositionSeconds: number }
  | { type: 'NEXT' }
  | { type: 'NEXT_PART' };

export type PlayerEventType = PlayerEvent['type'];

export const ALL_EVENT_TYPES: readonly PlayerEventType[] = [
  'LOAD', 'ITEM_NOT_FOUND', 'DECISION_DIRECT', 'DECISION_HLS', 'DECISION_UNSUPPORTED', 'PREPARING',
  'PREPARE_TICK', 'CANCEL', 'REQUEST_FAILED', 'CAN_PLAY', 'PLAYING', 'PAUSE', 'PLAY', 'PLAY_BLOCKED',
  'WAITING', 'ENDED', 'SWITCH_AUDIO', 'MEDIA_ERROR', 'PAUSE_RELEASE', 'RETRY', 'PLAY_FILE', 'NEXT', 'NEXT_PART',
];

export const RECOVERY_WINDOW_MS = 30_000;

export function initialPlayerState(): PlayerState {
  return {
    name: 'idle',
    mode: null,
    fileId: null,
    sessionId: null,
    url: null,
    audioStreamIndex: null,
    durationSeconds: null,
    subtitleTracks: [],
    unsupported: null,
    attachStart: 0,
    attachResetsPosition: false,
    attachSeq: 0,
    requestSeq: 0,
    resumeSeq: 0,
    pendingRequest: null,
    lastIntent: null,
    lastRecoveryAt: null,
    prepareStartedAt: null,
    prepareBudgetS: null,
    prepareEstimateS: null,
    prepareRetryDelayS: null,
    resumeState: null,
    switchFrom: null,
    sessionReleased: false,
    error: null,
    failedFileIds: [],
    hasPlayed: false,
    notice: null,
    exitReason: null,
  };
}

// --- Pure helpers used by the effects -------------------------------------------------------

/** `min(30 min, max(120 s, 2 × first estimate))`; 120 s without an estimate. */
export function prepareBudgetSeconds(firstEstimate: number | null): number {
  return firstEstimate == null ? 120 : Math.min(1800, Math.max(120, 2 * firstEstimate));
}

export function recoveryAllowed(state: PlayerState, now: number): boolean {
  return state.lastRecoveryAt == null || now - state.lastRecoveryAt >= RECOVERY_WINDOW_MS;
}

const REPORTING: ReadonlySet<PlayerStateName> = new Set(['playing', 'paused', 'buffering', 'ended']);
const SWITCHABLE: ReadonlySet<PlayerStateName> = new Set(['playing', 'paused', 'buffering']);
const MEDIA_ERROR_STATES: ReadonlySet<PlayerStateName> = new Set([
  'loading', 'blocked', 'playing', 'paused', 'buffering', 'recovering',
]);

/** The progress reporter runs only in these states. */
export const canReport = (name: PlayerStateName) => REPORTING.has(name);
/** The audio menu is enabled only where SWITCH_AUDIO is listed. */
export const canSwitchAudio = (name: PlayerStateName) => SWITCHABLE.has(name);
/** States in which a fatal media error is handled (elsewhere the source is a backdrop or gone). */
export const acceptsMediaError = (name: PlayerStateName) => MEDIA_ERROR_STATES.has(name);

const messageFor = (code: string) => errorMessage(new ApiError({ status: 0, code }));

/** The error screen kind of a failed `POST playback`. */
export function playerErrorFrom(err: unknown): PlayerError {
  if (!(err instanceof ApiError)) return { kind: 'failed', code: 'unknown', message: errorMessage(err), detail: null };
  const { code } = err;
  if (code === 'too_many_sessions' || code === 'hls_disk_full') {
    return { kind: 'busy', code, message: errorMessage(err), detail: null };
  }
  if (code === 'file_missing' || code === 'not_found') return { kind: 'gone', code, message: errorMessage(err), detail: null };
  if (code === 'transcoder_failed') {
    const detail = typeof err.details.detail === 'string' ? err.details.detail : null;
    if (detail?.startsWith('keyframe_index_failed')) {
      return { kind: 'version_failed', code, message: S.player.versionFailed, detail: null };
    }
    return { kind: 'failed', code, message: errorMessage(err), detail };
  }
  return { kind: 'failed', code, message: errorMessage(err), detail: null };
}

// --- Transitions ----------------------------------------------------------------------------

const PREPARE_RESET = {
  prepareStartedAt: null,
  prepareBudgetS: null,
  prepareEstimateS: null,
  prepareRetryDelayS: null,
} as const;

function request(s: PlayerState, origin: RequestOrigin, intent: PlaybackIntent, extra: Partial<PlayerState> = {}): PlayerState {
  return {
    ...s,
    ...extra,
    name: origin,
    pendingRequest: { origin, intent },
    lastIntent: intent,
    requestSeq: s.requestSeq + 1,
  };
}

/** A new session at the saved position, same file and audio (resume after the pause release, recovery). */
function sameSourceIntent(s: PlayerState, start: number, resetPosition = false): PlaybackIntent {
  return {
    fileId: s.fileId ?? s.lastIntent?.fileId ?? 0,
    audioStreamIndex: s.audioStreamIndex,
    startPositionSeconds: Math.max(0, start),
    previousSessionId: null,
    resetPosition,
  };
}

function decide(s: PlayerState, response: PlaybackDirect | PlaybackHls): PlayerState {
  const intent = s.pendingRequest?.intent ?? s.lastIntent;
  return {
    ...s,
    ...PREPARE_RESET,
    name: 'loading',
    mode: response.mode,
    fileId: intent?.fileId ?? s.fileId,
    url: response.url,
    sessionId: response.mode === 'hls' ? response.session_id : null,
    audioStreamIndex: response.audio_stream_index,
    durationSeconds: response.duration_seconds,
    subtitleTracks: response.subtitle_tracks,
    unsupported: null,
    error: null,
    attachStart: intent?.startPositionSeconds ?? 0,
    attachResetsPosition: intent?.resetPosition ?? false,
    attachSeq: s.attachSeq + 1,
    pendingRequest: null,
    sessionReleased: false,
    switchFrom: null,
    resumeState: null,
  };
}

function toUnsupported(s: PlayerState, response: PlaybackUnsupported): PlayerState {
  const intent = s.pendingRequest?.intent ?? s.lastIntent;
  return {
    ...s,
    ...PREPARE_RESET,
    name: 'unsupported',
    fileId: intent?.fileId ?? s.fileId,
    mode: null,
    sessionId: null,
    url: null,
    unsupported: { reason: response.reason, detail: response.detail },
    pendingRequest: null,
  };
}

function toError(s: PlayerState, error: PlayerError): PlayerState {
  const fileId = s.pendingRequest?.intent.fileId ?? s.fileId;
  const failedFileIds =
    error.kind === 'version_failed' && fileId != null && !s.failedFileIds.includes(fileId)
      ? [...s.failedFileIds, fileId]
      : s.failedFileIds;
  return { ...s, ...PREPARE_RESET, name: 'error', error, failedFileIds, pendingRequest: null, switchFrom: null };
}

function preparingFrom(s: PlayerState, e: Extract<PlayerEvent, { type: 'PREPARING' }>): PlayerState {
  const firstOfWait = s.prepareStartedAt == null;
  return {
    ...s,
    name: 'preparing',
    prepareStartedAt: firstOfWait ? e.now : s.prepareStartedAt,
    prepareBudgetS: firstOfWait ? prepareBudgetSeconds(e.estimatedSeconds) : s.prepareBudgetS,
    prepareEstimateS: e.estimatedSeconds,
    prepareRetryDelayS: e.retryAfterSeconds,
  };
}

let noticeSeq = 0;
const audioUnsupportedNotice = (): PlayerNotice => ({ kind: 'audio_unsupported', seq: ++noticeSeq });
const switchFailedNotice = (message: string): PlayerNotice => ({ kind: 'switch_failed', message, seq: ++noticeSeq });

/** Back to the previous audio after a failed/cancelled switch. */
function restoreAfterSwitch(s: PlayerState, notice: PlayerNotice | null): PlayerState {
  const from = s.switchFrom;
  if (!from) return s;
  const base = { ...s, ...PREPARE_RESET, switchFrom: null, notice: notice ?? s.notice };
  if (!from.oldSessionReleased) {
    // Direct mode: the old source is still valid; resume it in place.
    return { ...base, name: s.resumeState ?? 'paused', resumeState: null, pendingRequest: null, resumeSeq: s.resumeSeq + 1 };
  }
  // HLS: the old session is gone (the server stopped it); restore prevAudio with a new request at t (RESTORE_AUDIO).
  return request({ ...base, resumeState: null }, 'requesting', {
    fileId: s.fileId ?? 0,
    audioStreamIndex: from.prevAudio,
    startPositionSeconds: from.t,
    previousSessionId: null,
    resetPosition: false,
  });
}

function mediaError(s: PlayerState, e: Extract<PlayerEvent, { type: 'MEDIA_ERROR' }>): PlayerState {
  if (e.kind === 'gone') {
    return toError(s, { kind: 'gone', code: 'file_missing', message: messageFor('file_missing'), detail: null });
  }
  if (e.kind === 'decode') {
    return toError(s, { kind: 'decode', code: e.code ?? 'decode_error', message: S.player.decodeFailed, detail: null });
  }
  if (s.name !== 'recovering' && recoveryAllowed(s, e.now)) {
    return request(s, 'recovering', sameSourceIntent(s, e.position), { lastRecoveryAt: e.now });
  }
  const code = e.code ?? 'network_error';
  return toError(s, { kind: 'failed', code, message: S.player.playbackError(messageFor(code)), detail: null });
}

function switchAudio(s: PlayerState, e: Extract<PlayerEvent, { type: 'SWITCH_AUDIO' }>, resumeState: ResumeState): PlayerState {
  if (e.audioStreamIndex === s.audioStreamIndex) return s;
  const handOver = s.mode === 'hls' && !s.sessionReleased && s.sessionId != null;
  return request(
    s,
    'switching',
    {
      fileId: s.fileId ?? 0,
      audioStreamIndex: e.audioStreamIndex,
      startPositionSeconds: Math.max(0, e.t),
      previousSessionId: handOver ? s.sessionId : null,
      resetPosition: false,
    },
    { resumeState, switchFrom: { t: Math.max(0, e.t), prevAudio: s.audioStreamIndex, oldSessionReleased: s.mode === 'hls' } },
  );
}

const releasable = (s: PlayerState) => s.mode === 'hls' && !s.sessionReleased;
const played = (s: PlayerState): PlayerState => ({ ...s, name: 'playing', hasPlayed: true });

type Handler = (s: PlayerState, e: PlayerEvent) => PlayerState;

const HANDLERS: Record<PlayerStateName, Handler> = {
  idle: (s, e) => {
    if (e.type === 'LOAD') {
      return request(s, 'requesting', {
        fileId: e.fileId,
        audioStreamIndex: e.audioStreamIndex ?? null,
        startPositionSeconds: Math.max(0, e.startPositionSeconds),
        previousSessionId: null,
        resetPosition: false,
      });
    }
    return s;
  },

  requesting: (s, e) => {
    switch (e.type) {
      case 'DECISION_DIRECT':
      case 'DECISION_HLS':
        return decide(s, e.response);
      case 'DECISION_UNSUPPORTED':
        return toUnsupported(s, e.response);
      case 'PREPARING':
        return preparingFrom(s, e);
      case 'REQUEST_FAILED':
        return toError(s, e.error);
      default:
        return s;
    }
  },

  preparing: (s, e) => {
    const origin = s.pendingRequest?.origin ?? 'requesting';
    if (e.type === 'PREPARE_TICK') {
      if (s.prepareStartedAt != null && s.prepareBudgetS != null && e.now - s.prepareStartedAt >= s.prepareBudgetS * 1000) {
        return toError(s, { kind: 'prepare_timeout', code: 'preparing', message: S.player.prepareTimeout, detail: null });
      }
      return { ...s, name: origin, requestSeq: s.requestSeq + 1 };
    }
    if (e.type === 'CANCEL') {
      if (origin === 'requesting') return { ...s, ...PREPARE_RESET, name: 'exited', exitReason: 'cancel', pendingRequest: null };
      if (origin === 'switching') return restoreAfterSwitch(s, null);
      return toError(s, {
        kind: 'failed',
        code: 'recovery_cancelled',
        message: S.player.playbackError(S.player.recoveryCancelled),
        detail: null,
      });
    }
    return s;
  },

  loading: (s, e) => {
    switch (e.type) {
      case 'CAN_PLAY':
        return { ...s, name: 'playing' };
      case 'PLAYING':
        return played(s);
      case 'PAUSE':
        return { ...s, name: 'paused' };
      case 'PLAY_BLOCKED':
        return { ...s, name: 'blocked' };
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  blocked: (s, e) => {
    switch (e.type) {
      case 'PLAYING':
        return s.sessionReleased ? s : played(s);
      case 'PAUSE_RELEASE':
        return releasable(s) ? { ...s, sessionReleased: true } : s;
      case 'PLAY':
        return s.sessionReleased ? request(s, 'requesting', sameSourceIntent(s, e.position)) : s;
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  playing: (s, e) => {
    switch (e.type) {
      case 'PAUSE':
        return { ...s, name: 'paused' };
      case 'WAITING':
        return { ...s, name: 'buffering' };
      case 'ENDED':
        return { ...s, name: 'ended' };
      case 'SWITCH_AUDIO':
        return switchAudio(s, e, 'playing');
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  paused: (s, e) => {
    switch (e.type) {
      case 'PLAY':
        return s.sessionReleased ? request(s, 'requesting', sameSourceIntent(s, e.position)) : { ...s, name: 'playing' };
      case 'PLAYING':
        return s.sessionReleased ? s : played(s);
      case 'ENDED':
        return { ...s, name: 'ended' };
      case 'PLAY_BLOCKED':
        return { ...s, name: 'blocked' };
      case 'PAUSE_RELEASE':
        return releasable(s) ? { ...s, sessionReleased: true } : s;
      case 'SWITCH_AUDIO':
        return switchAudio(s, e, 'paused');
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  buffering: (s, e) => {
    switch (e.type) {
      case 'PLAYING':
        return played(s);
      case 'PAUSE':
        return { ...s, name: 'paused' };
      case 'ENDED':
        return { ...s, name: 'ended' };
      case 'SWITCH_AUDIO':
        return switchAudio(s, e, 'playing');
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  switching: (s, e) => {
    switch (e.type) {
      case 'DECISION_DIRECT':
      case 'DECISION_HLS':
        return decide(s, e.response);
      case 'DECISION_UNSUPPORTED':
        return restoreAfterSwitch(s, audioUnsupportedNotice());
      case 'PREPARING':
        return preparingFrom(s, e);
      case 'REQUEST_FAILED':
        if (e.error.kind === 'version_failed') return restoreAfterSwitch(s, audioUnsupportedNotice());
        if (e.error.kind === 'busy' && s.switchFrom && !s.switchFrom.oldSessionReleased) {
          return restoreAfterSwitch(s, switchFailedNotice(e.error.message));
        }
        return toError(s, e.error);
      default:
        return s;
    }
  },

  recovering: (s, e) => {
    switch (e.type) {
      case 'DECISION_DIRECT':
      case 'DECISION_HLS':
        return decide(s, e.response);
      case 'DECISION_UNSUPPORTED':
        return toUnsupported(s, e.response);
      case 'PREPARING':
        return preparingFrom(s, e);
      case 'REQUEST_FAILED':
        return toError(s, e.error);
      case 'MEDIA_ERROR':
        return mediaError(s, e);
      default:
        return s;
    }
  },

  ended: (s, e) => {
    switch (e.type) {
      case 'PLAY':
        return s.sessionReleased ? request(s, 'requesting', sameSourceIntent(s, 0, true)) : { ...s, name: 'playing' };
      case 'PLAYING':
        return s.sessionReleased ? s : played(s);
      case 'PAUSE_RELEASE':
        return releasable(s) ? { ...s, sessionReleased: true } : s;
      case 'NEXT':
        return { ...s, name: 'exited', exitReason: 'next' };
      case 'NEXT_PART':
        return { ...s, name: 'exited', exitReason: 'next_part' };
      default:
        return s;
    }
  },

  unsupported: (s, e) => retryOrPlayFile(s, e),

  error: (s, e) => retryOrPlayFile(s, e),

  exited: (s) => s,
};

function retryOrPlayFile(s: PlayerState, e: PlayerEvent): PlayerState {
  const reset = { ...PREPARE_RESET, lastRecoveryAt: null, error: null, unsupported: null };
  if (e.type === 'RETRY') {
    if (s.error?.kind === 'version_failed' || !s.lastIntent) return s;
    return request(s, 'requesting', { ...s.lastIntent, startPositionSeconds: Math.max(0, e.position), previousSessionId: null }, reset);
  }
  if (e.type === 'PLAY_FILE') {
    return request(
      s,
      'requesting',
      {
        fileId: e.fileId,
        audioStreamIndex: null,
        startPositionSeconds: Math.max(0, e.startPositionSeconds),
        previousSessionId: null,
        resetPosition: true,
      },
      { ...reset, hasPlayed: false, fileId: e.fileId, mode: null, sessionId: null, url: null, subtitleTracks: [] },
    );
  }
  return s;
}

export function playerReducer(s: PlayerState, e: PlayerEvent): PlayerState {
  if (e.type === 'ITEM_NOT_FOUND' && s.name !== 'error' && s.name !== 'exited') {
    return toError(s, { kind: 'gone', code: 'not_found', message: messageFor('not_found'), detail: null });
  }
  return HANDLERS[s.name](s, e);
}
