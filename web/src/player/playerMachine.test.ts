import { describe, expect, it } from 'vitest';
import { ApiError } from '@/api/errors';
import * as f from '../../tests/fixtures';
import {
  ALL_EVENT_TYPES,
  ALL_STATE_NAMES,
  canReport,
  canSwitchAudio,
  initialPlayerState,
  playerErrorFrom,
  playerReducer,
  prepareBudgetSeconds,
  recoveryAllowed,
  type PlaybackIntent,
  type PlayerEvent,
  type PlayerEventType,
  type PlayerState,
  type PlayerStateName,
} from './playerMachine';

// --- Builders ------------------------------------------------------------------------------

const FILE = 91;
const hls = f.playbackHls({ session_id: 'sess-1', audio_stream_index: 1 });
const direct = f.playbackDirect({ audio_stream_index: 1 });
const intent = (overrides: Partial<PlaybackIntent> = {}): PlaybackIntent => ({
  fileId: FILE,
  audioStreamIndex: null,
  startPositionSeconds: 100,
  previousSessionId: null,
  resetPosition: false,
  ...overrides,
});

const run = (state: PlayerState, ...events: PlayerEvent[]) => events.reduce(playerReducer, state);

const requesting = () => run(initialPlayerState(), { type: 'LOAD', fileId: FILE, startPositionSeconds: 100 });
const loadingHls = () => run(requesting(), { type: 'DECISION_HLS', response: hls });
const loadingDirect = () => run(requesting(), { type: 'DECISION_DIRECT', response: direct });
const playingHls = () => run(loadingHls(), { type: 'PLAYING' });
const playingDirect = () => run(loadingDirect(), { type: 'PLAYING' });
const pausedHls = () => run(playingHls(), { type: 'PAUSE' });
const pausedDirect = () => run(playingDirect(), { type: 'PAUSE' });
const blocked = () => run(loadingHls(), { type: 'PLAY_BLOCKED' });
const buffering = () => run(playingHls(), { type: 'WAITING' });
const ended = () => run(playingHls(), { type: 'ENDED' });
const switchingHls = (from = playingHls()) => run(from, { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 });
const switchingDirect = (from = playingDirect()) => run(from, { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 });
const recovering = (now = 0) =>
  run(playingHls(), { type: 'MEDIA_ERROR', now, source: 'hls', kind: 'network', position: 400 });
const preparing = (from = requesting(), estimatedSeconds: number | null = 30) =>
  run(from, { type: 'PREPARING', now: 1_000, estimatedSeconds, retryAfterSeconds: 5 });
const unsupported = () => run(requesting(), { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() });
const failed = (code = 'too_many_sessions', status = 503) =>
  run(requesting(), { type: 'REQUEST_FAILED', error: playerErrorFrom(new ApiError({ status, code })) });
const versionFailed = () =>
  run(requesting(), {
    type: 'REQUEST_FAILED',
    error: playerErrorFrom(
      new ApiError({ status: 500, code: 'transcoder_failed', details: { detail: 'keyframe_index_failed: stalled' } }),
    ),
  });

// --- Error classification -----------------------------------------------------------------

describe('playerErrorFrom', () => {
  it.each([
    [503, 'too_many_sessions', {}, 'busy'],
    [503, 'hls_disk_full', {}, 'busy'],
    [404, 'file_missing', {}, 'gone'],
    [404, 'not_found', {}, 'gone'],
    [500, 'transcoder_failed', { detail: 'keyframe_index_failed: stalled' }, 'version_failed'],
    [500, 'transcoder_failed', { detail: 'ffmpeg exited 1' }, 'failed'],
    [0, 'network_error', {}, 'failed'],
    [409, 'weird_code', {}, 'failed'],
  ])('%s %s → %s', (status, code, details, kind) => {
    expect(playerErrorFrom(new ApiError({ status, code, details })).kind).toBe(kind);
  });

  it('uses catalog messages and keeps only the transcoder_failed diagnostic as detail', () => {
    expect(playerErrorFrom(new ApiError({ status: 503, code: 'hls_disk_full' })).message).toBe(
      'El servidor no tiene espacio para convertir vídeo ahora mismo. Vuelve a intentarlo en unos minutos o detén la reproducción en otro dispositivo.',
    );
    const transcoder = playerErrorFrom(
      new ApiError({ status: 500, code: 'transcoder_failed', message: 'English', details: { detail: 'ffmpeg exited 1' } }),
    );
    expect(transcoder).toEqual({
      kind: 'failed',
      code: 'transcoder_failed',
      message: 'El servidor no ha podido preparar este archivo para reproducirlo.',
      detail: 'ffmpeg exited 1',
    });
    const keyframes = playerErrorFrom(
      new ApiError({ status: 500, code: 'transcoder_failed', details: { detail: 'keyframe_index_failed: stalled' } }),
    );
    expect(keyframes.message).toBe('El servidor no puede preparar esta versión para reproducirla.');
    expect(keyframes.detail).toBeNull();
    expect(playerErrorFrom(new ApiError({ status: 409, code: 'weird_code', message: 'English text' })).message).toBe(
      'Se ha producido un error inesperado (weird_code).',
    );
    expect(playerErrorFrom(new Error('boom')).kind).toBe('failed');
  });
});

// --- The state table ----------------------------------------------------------------------

interface Row {
  name: string;
  from: () => PlayerState;
  event: PlayerEvent;
  to: PlayerStateName;
  check?: (next: PlayerState, prev: PlayerState) => void;
}

const busyError = playerErrorFrom(new ApiError({ status: 503, code: 'hls_disk_full' }));
const otherError = playerErrorFrom(new ApiError({ status: 0, code: 'network_error' }));
const missingError = playerErrorFrom(new ApiError({ status: 404, code: 'file_missing' }));
const versionError = playerErrorFrom(
  new ApiError({ status: 500, code: 'transcoder_failed', details: { detail: 'keyframe_index_failed: x' } }),
);

const ROWS: Row[] = [
  // idle
  {
    name: 'idle LOAD → requesting with the start position',
    from: initialPlayerState,
    event: { type: 'LOAD', fileId: FILE, startPositionSeconds: 100 },
    to: 'requesting',
    check: (s) => {
      expect(s.pendingRequest).toEqual({ origin: 'requesting', intent: intent() });
      expect(s.requestSeq).toBe(1);
    },
  },
  {
    name: 'idle ITEM_NOT_FOUND → error gone',
    from: initialPlayerState,
    event: { type: 'ITEM_NOT_FOUND' },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('gone'),
  },
  // requesting
  {
    name: 'requesting DECISION_HLS → loading',
    from: requesting,
    event: { type: 'DECISION_HLS', response: hls },
    to: 'loading',
    check: (s, prev) => {
      expect(s).toMatchObject({ mode: 'hls', sessionId: 'sess-1', url: hls.url, attachStart: 100, fileId: FILE });
      expect(s.attachSeq).toBe(prev.attachSeq + 1);
      expect(s.pendingRequest).toBeNull();
    },
  },
  {
    name: 'requesting DECISION_DIRECT → loading',
    from: requesting,
    event: { type: 'DECISION_DIRECT', response: direct },
    to: 'loading',
    check: (s) => expect(s).toMatchObject({ mode: 'direct', sessionId: null, durationSeconds: 9360.5 }),
  },
  {
    name: 'requesting DECISION_UNSUPPORTED → unsupported',
    from: requesting,
    event: { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported({ reason: 'keyframes_too_sparse' }) },
    to: 'unsupported',
    check: (s) => expect(s.unsupported?.reason).toBe('keyframes_too_sparse'),
  },
  {
    name: 'requesting PREPARING → preparing (origin requesting)',
    from: requesting,
    event: { type: 'PREPARING', now: 1_000, estimatedSeconds: 150, retryAfterSeconds: 5 },
    to: 'preparing',
    check: (s) => {
      expect(s.pendingRequest?.origin).toBe('requesting');
      expect(s).toMatchObject({ prepareStartedAt: 1_000, prepareBudgetS: 300, prepareEstimateS: 150, prepareRetryDelayS: 5 });
    },
  },
  {
    name: 'requesting REQUEST_FAILED busy → error busy',
    from: requesting,
    event: { type: 'REQUEST_FAILED', error: busyError },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('busy'),
  },
  {
    name: 'requesting REQUEST_FAILED version_failed → error and the file is remembered',
    from: requesting,
    event: { type: 'REQUEST_FAILED', error: versionError },
    to: 'error',
    check: (s) => {
      expect(s.error?.kind).toBe('version_failed');
      expect(s.failedFileIds).toEqual([FILE]);
    },
  },
  // preparing
  {
    name: 'preparing PREPARE_TICK before the budget → back to requesting with the same body',
    from: () => preparing(),
    event: { type: 'PREPARE_TICK', now: 1_000 + 119_000 },
    to: 'requesting',
    check: (s, prev) => {
      expect(s.pendingRequest).toEqual(prev.pendingRequest);
      expect(s.requestSeq).toBe(prev.requestSeq + 1);
      expect(s.prepareStartedAt).toBe(1_000);
    },
  },
  {
    name: 'preparing PREPARE_TICK at the budget → error prepare_timeout',
    from: () => preparing(),
    event: { type: 'PREPARE_TICK', now: 1_000 + 120_000 },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('prepare_timeout'),
  },
  {
    name: 'preparing PREPARE_TICK (origin switching) → switching',
    from: () => preparing(switchingHls()),
    event: { type: 'PREPARE_TICK', now: 2_000 },
    to: 'switching',
  },
  {
    name: 'preparing PREPARE_TICK (origin recovering) → recovering',
    from: () => preparing(recovering()),
    event: { type: 'PREPARE_TICK', now: 2_000 },
    to: 'recovering',
  },
  {
    name: 'preparing CANCEL (origin requesting) → exited',
    from: () => preparing(),
    event: { type: 'CANCEL' },
    to: 'exited',
  },
  {
    name: 'preparing CANCEL (origin switching, direct) → resumeState on the old source',
    from: () => preparing(switchingDirect()),
    event: { type: 'CANCEL' },
    to: 'playing',
    check: (s, prev) => {
      expect(s.audioStreamIndex).toBe(1);
      expect(s.mode).toBe('direct');
      expect(s.resumeSeq).toBe(prev.resumeSeq + 1);
      expect(s.switchFrom).toBeNull();
      expect(s.prepareStartedAt).toBeNull();
    },
  },
  {
    name: 'preparing CANCEL (origin switching from paused, direct) → paused',
    from: () => preparing(switchingDirect(pausedDirect())),
    event: { type: 'CANCEL' },
    to: 'paused',
  },
  {
    name: 'preparing CANCEL (origin switching, HLS) → requesting prevAudio at t (RESTORE_AUDIO)',
    from: () => preparing(switchingHls()),
    event: { type: 'CANCEL' },
    to: 'requesting',
    check: (s) =>
      expect(s.pendingRequest).toEqual({
        origin: 'requesting',
        intent: intent({ audioStreamIndex: 1, startPositionSeconds: 400, previousSessionId: null }),
      }),
  },
  {
    name: 'preparing CANCEL (origin recovering) → error failed',
    from: () => preparing(recovering()),
    event: { type: 'CANCEL' },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('failed'),
  },
  // loading
  { name: 'loading CAN_PLAY → playing', from: loadingHls, event: { type: 'CAN_PLAY' }, to: 'playing' },
  {
    name: 'loading PLAYING → playing and hasPlayed',
    from: loadingHls,
    event: { type: 'PLAYING' },
    to: 'playing',
    check: (s) => expect(s.hasPlayed).toBe(true),
  },
  {
    name: 'loading PAUSE → paused (nothing played yet)',
    from: loadingHls,
    event: { type: 'PAUSE' },
    to: 'paused',
    check: (s) => expect(s.hasPlayed).toBe(false),
  },
  { name: 'loading PLAY_BLOCKED → blocked', from: loadingHls, event: { type: 'PLAY_BLOCKED' }, to: 'blocked' },
  {
    name: 'loading MEDIA_ERROR network → recovering',
    from: loadingHls,
    event: { type: 'MEDIA_ERROR', now: 5_000, source: 'hls', kind: 'network', position: 100 },
    to: 'recovering',
    check: (s) => {
      expect(s.lastRecoveryAt).toBe(5_000);
      expect(s.pendingRequest).toEqual({ origin: 'recovering', intent: intent({ audioStreamIndex: 1 }) });
    },
  },
  {
    name: 'loading MEDIA_ERROR gone → error gone',
    from: loadingDirect,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'direct', kind: 'gone', position: 0 },
    to: 'error',
    check: (s) => expect(s.error).toMatchObject({ kind: 'gone', code: 'file_missing' }),
  },
  {
    name: 'loading MEDIA_ERROR decode → error decode',
    from: loadingDirect,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'direct', kind: 'decode', position: 0 },
    to: 'error',
    check: (s) =>
      expect(s.error).toMatchObject({ kind: 'decode', message: 'Este navegador no ha podido descodificar el archivo.' }),
  },
  // blocked
  {
    name: 'blocked PLAYING → playing',
    from: blocked,
    event: { type: 'PLAYING' },
    to: 'playing',
    check: (s) => expect(s.hasPlayed).toBe(true),
  },
  {
    name: 'blocked PAUSE_RELEASE → blocked with sessionReleased',
    from: blocked,
    event: { type: 'PAUSE_RELEASE' },
    to: 'blocked',
    check: (s) => expect(s.sessionReleased).toBe(true),
  },
  {
    name: 'blocked (released) PLAY → requesting at the saved position',
    from: () => run(blocked(), { type: 'PAUSE_RELEASE' }),
    event: { type: 'PLAY', position: 100 },
    to: 'requesting',
    check: (s) => expect(s.pendingRequest?.intent).toEqual(intent({ audioStreamIndex: 1 })),
  },
  {
    name: 'blocked MEDIA_ERROR → recovering',
    from: blocked,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'hls', kind: 'network', position: 100 },
    to: 'recovering',
  },
  // playing
  { name: 'playing PAUSE → paused', from: playingHls, event: { type: 'PAUSE' }, to: 'paused' },
  { name: 'playing WAITING → buffering', from: playingHls, event: { type: 'WAITING' }, to: 'buffering' },
  { name: 'playing ENDED → ended', from: playingHls, event: { type: 'ENDED' }, to: 'ended' },
  {
    name: 'playing SWITCH_AUDIO (HLS) → switching, previous_session_id sent',
    from: playingHls,
    event: { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 },
    to: 'switching',
    check: (s) => {
      expect(s.resumeState).toBe('playing');
      expect(s.switchFrom).toEqual({ t: 400, prevAudio: 1, oldSessionReleased: true });
      expect(s.pendingRequest).toEqual({
        origin: 'switching',
        intent: intent({ audioStreamIndex: 2, startPositionSeconds: 400, previousSessionId: 'sess-1' }),
      });
    },
  },
  {
    name: 'playing SWITCH_AUDIO (direct) → switching, no previous_session_id',
    from: playingDirect,
    event: { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 },
    to: 'switching',
    check: (s) => {
      expect(s.switchFrom).toEqual({ t: 400, prevAudio: 1, oldSessionReleased: false });
      expect(s.pendingRequest?.intent.previousSessionId).toBeNull();
    },
  },
  {
    name: 'playing MEDIA_ERROR → recovering',
    from: playingHls,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'hls', kind: 'network', position: 400 },
    to: 'recovering',
  },
  // paused
  { name: 'paused PLAY → playing', from: pausedHls, event: { type: 'PLAY', position: 400 }, to: 'playing' },
  {
    name: 'paused (released) PLAY → requesting at the saved position, no previous session',
    from: () => run(pausedHls(), { type: 'PAUSE_RELEASE' }),
    event: { type: 'PLAY', position: 4325 },
    to: 'requesting',
    check: (s) =>
      expect(s.pendingRequest).toEqual({
        origin: 'requesting',
        intent: intent({ audioStreamIndex: 1, startPositionSeconds: 4325 }),
      }),
  },
  { name: 'paused PLAYING → playing', from: pausedHls, event: { type: 'PLAYING' }, to: 'playing' },
  { name: 'paused ENDED → ended', from: pausedHls, event: { type: 'ENDED' }, to: 'ended' },
  { name: 'paused PLAY_BLOCKED → blocked', from: pausedHls, event: { type: 'PLAY_BLOCKED' }, to: 'blocked' },
  {
    name: 'paused PAUSE_RELEASE → paused with sessionReleased',
    from: pausedHls,
    event: { type: 'PAUSE_RELEASE' },
    to: 'paused',
    check: (s) => expect(s.sessionReleased).toBe(true),
  },
  {
    name: 'paused SWITCH_AUDIO → switching (resumeState paused)',
    from: pausedHls,
    event: { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 },
    to: 'switching',
    check: (s) => expect(s.resumeState).toBe('paused'),
  },
  {
    name: 'paused MEDIA_ERROR decode → error decode',
    from: pausedHls,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'native', kind: 'decode', position: 0 },
    to: 'error',
  },
  // buffering
  { name: 'buffering PLAYING → playing', from: buffering, event: { type: 'PLAYING' }, to: 'playing' },
  { name: 'buffering PAUSE → paused', from: buffering, event: { type: 'PAUSE' }, to: 'paused' },
  { name: 'buffering ENDED → ended', from: buffering, event: { type: 'ENDED' }, to: 'ended' },
  {
    name: 'buffering SWITCH_AUDIO → switching with resumeState playing',
    from: buffering,
    event: { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 400 },
    to: 'switching',
    check: (s) => expect(s.resumeState).toBe('playing'),
  },
  {
    name: 'buffering MEDIA_ERROR → recovering',
    from: buffering,
    event: { type: 'MEDIA_ERROR', now: 0, source: 'native', kind: 'network', position: 400 },
    to: 'recovering',
  },
  // switching
  {
    name: 'switching DECISION_HLS → loading at t with the new audio',
    from: () => switchingHls(),
    event: { type: 'DECISION_HLS', response: f.playbackHls({ session_id: 'sess-2', audio_stream_index: 2 }) },
    to: 'loading',
    check: (s) => {
      expect(s).toMatchObject({ sessionId: 'sess-2', audioStreamIndex: 2, attachStart: 400, switchFrom: null });
    },
  },
  {
    name: 'switching DECISION_DIRECT → loading',
    from: () => switchingDirect(),
    event: { type: 'DECISION_DIRECT', response: f.playbackDirect({ audio_stream_index: 2 }) },
    to: 'loading',
  },
  {
    name: 'switching (direct) DECISION_UNSUPPORTED → toast + resumeState on the old source',
    from: () => switchingDirect(),
    event: { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported({ reason: 'keyframes_too_sparse' }) },
    to: 'playing',
    check: (s, prev) => {
      expect(s.notice).toMatchObject({ kind: 'audio_unsupported' });
      expect(s.resumeSeq).toBe(prev.resumeSeq + 1);
      expect(s.audioStreamIndex).toBe(1);
    },
  },
  {
    name: 'switching (HLS) DECISION_UNSUPPORTED → toast + requesting prevAudio at t',
    from: () => switchingHls(),
    event: { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() },
    to: 'requesting',
    check: (s) => {
      expect(s.notice).toMatchObject({ kind: 'audio_unsupported' });
      expect(s.pendingRequest?.intent).toEqual(intent({ audioStreamIndex: 1, startPositionSeconds: 400 }));
    },
  },
  {
    name: 'switching PREPARING → preparing (origin switching)',
    from: () => switchingHls(),
    event: { type: 'PREPARING', now: 0, estimatedSeconds: null, retryAfterSeconds: 5 },
    to: 'preparing',
    check: (s) => expect(s.pendingRequest?.origin).toBe('switching'),
  },
  {
    name: 'switching (direct) REQUEST_FAILED busy → toast + resumeState',
    from: () => switchingDirect(pausedDirect()),
    event: { type: 'REQUEST_FAILED', error: busyError },
    to: 'paused',
    check: (s) => expect(s.notice).toMatchObject({ kind: 'switch_failed', message: busyError.message }),
  },
  {
    name: 'switching (HLS) REQUEST_FAILED busy → error busy, Retry at t with the chosen audio',
    from: () => switchingHls(),
    event: { type: 'REQUEST_FAILED', error: busyError },
    to: 'error',
    check: (s) => {
      expect(s.error?.kind).toBe('busy');
      expect(s.lastIntent).toEqual(intent({ audioStreamIndex: 2, startPositionSeconds: 400, previousSessionId: 'sess-1' }));
    },
  },
  {
    name: 'switching (direct) REQUEST_FAILED version_failed → like unsupported',
    from: () => switchingDirect(),
    event: { type: 'REQUEST_FAILED', error: versionError },
    to: 'playing',
    check: (s) => {
      expect(s.notice).toMatchObject({ kind: 'audio_unsupported' });
      expect(s.failedFileIds).toEqual([]);
    },
  },
  {
    name: 'switching other REQUEST_FAILED → error',
    from: () => switchingHls(),
    event: { type: 'REQUEST_FAILED', error: otherError },
    to: 'error',
  },
  // recovering
  {
    name: 'recovering DECISION_HLS → loading',
    from: () => recovering(),
    event: { type: 'DECISION_HLS', response: f.playbackHls({ session_id: 'sess-2' }) },
    to: 'loading',
    check: (s) => expect(s.attachStart).toBe(400),
  },
  {
    name: 'recovering DECISION_DIRECT → loading',
    from: () => recovering(),
    event: { type: 'DECISION_DIRECT', response: direct },
    to: 'loading',
  },
  {
    name: 'recovering DECISION_UNSUPPORTED → unsupported',
    from: () => recovering(),
    event: { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() },
    to: 'unsupported',
  },
  {
    name: 'recovering PREPARING → preparing (origin recovering)',
    from: () => recovering(),
    event: { type: 'PREPARING', now: 0, estimatedSeconds: 60, retryAfterSeconds: 5 },
    to: 'preparing',
  },
  {
    name: 'recovering REQUEST_FAILED file_missing → error gone',
    from: () => recovering(),
    event: { type: 'REQUEST_FAILED', error: missingError },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('gone'),
  },
  {
    name: 'recovering MEDIA_ERROR → error',
    from: () => recovering(),
    event: { type: 'MEDIA_ERROR', now: 50_000, source: 'hls', kind: 'network', position: 400 },
    to: 'error',
  },
  // ended
  {
    name: 'ended PLAY (play again) → playing',
    from: ended,
    event: { type: 'PLAY', position: 9360 },
    to: 'playing',
  },
  {
    name: 'ended (released) PLAY → requesting at 0',
    from: () => run(ended(), { type: 'PAUSE_RELEASE' }),
    event: { type: 'PLAY', position: 9360 },
    to: 'requesting',
    check: (s) =>
      expect(s.pendingRequest?.intent).toEqual(
        intent({ audioStreamIndex: 1, startPositionSeconds: 0, resetPosition: true }),
      ),
  },
  { name: 'ended PLAYING → playing', from: ended, event: { type: 'PLAYING' }, to: 'playing' },
  {
    name: 'ended PAUSE_RELEASE → ended with sessionReleased',
    from: ended,
    event: { type: 'PAUSE_RELEASE' },
    to: 'ended',
    check: (s) => expect(s.sessionReleased).toBe(true),
  },
  {
    name: 'ended NEXT → exited',
    from: ended,
    event: { type: 'NEXT' },
    to: 'exited',
    check: (s) => expect(s.exitReason).toBe('next'),
  },
  {
    name: 'ended NEXT_PART → exited',
    from: ended,
    event: { type: 'NEXT_PART' },
    to: 'exited',
    check: (s) => expect(s.exitReason).toBe('next_part'),
  },
  // unsupported / error
  {
    name: 'unsupported RETRY → requesting',
    from: unsupported,
    event: { type: 'RETRY', position: 100 },
    to: 'requesting',
  },
  {
    name: 'unsupported PLAY_FILE → requesting with that file',
    from: unsupported,
    event: { type: 'PLAY_FILE', fileId: 92, startPositionSeconds: 0 },
    to: 'requesting',
    check: (s) =>
      expect(s.pendingRequest?.intent).toEqual(
        intent({ fileId: 92, startPositionSeconds: 0, resetPosition: true }),
      ),
  },
  {
    name: 'error RETRY → requesting at the saved position',
    from: () => failed(),
    event: { type: 'RETRY', position: 250 },
    to: 'requesting',
    check: (s) => expect(s.pendingRequest?.intent).toEqual(intent({ startPositionSeconds: 250 })),
  },
  {
    name: 'error PLAY_FILE → requesting with that file',
    from: versionFailed,
    event: { type: 'PLAY_FILE', fileId: 92, startPositionSeconds: 100 },
    to: 'requesting',
    check: (s) => expect(s.failedFileIds).toEqual([FILE]),
  },
];

// ITEM_NOT_FOUND (the item 404 of the home-fallback start, "whichever arrives first") is
// accepted by every state except an already-shown error and exited.
const ITEM_NOT_FOUND_FROM: Array<[PlayerStateName, () => PlayerState]> = [
  ['requesting', requesting],
  ['preparing', () => preparing()],
  ['loading', loadingHls],
  ['blocked', blocked],
  ['playing', playingHls],
  ['paused', pausedHls],
  ['buffering', buffering],
  ['switching', () => switchingHls()],
  ['recovering', () => recovering()],
  ['ended', ended],
  ['unsupported', unsupported],
];
for (const [name, from] of ITEM_NOT_FOUND_FROM) {
  ROWS.push({
    name: `${name} ITEM_NOT_FOUND → error gone`,
    from,
    event: { type: 'ITEM_NOT_FOUND' },
    to: 'error',
    check: (s) => expect(s.error?.kind).toBe('gone'),
  });
}

describe('playerReducer: state table', () => {
  it.each(ROWS.map((r) => [r.name, r] as const))('%s', (_name, row) => {
    const prev = row.from();
    const next = playerReducer(prev, row.event);
    expect(next.name).toBe(row.to);
    expect(next).not.toBe(prev);
    row.check?.(next, prev);
  });
});

// --- Generated sweep: every unlisted (state, event) pair is a no-op ------------------------

const SAMPLE_STATES: Record<PlayerStateName, () => PlayerState> = {
  idle: initialPlayerState,
  requesting,
  preparing: () => preparing(),
  loading: loadingHls,
  blocked,
  playing: playingHls,
  paused: pausedHls,
  buffering,
  switching: () => switchingHls(),
  recovering: () => recovering(),
  ended,
  unsupported,
  error: () => failed(),
  exited: () => run(preparing(), { type: 'CANCEL' }),
};

const SAMPLE_EVENTS: Record<PlayerEventType, PlayerEvent> = {
  LOAD: { type: 'LOAD', fileId: FILE, startPositionSeconds: 0 },
  ITEM_NOT_FOUND: { type: 'ITEM_NOT_FOUND' },
  DECISION_DIRECT: { type: 'DECISION_DIRECT', response: direct },
  DECISION_HLS: { type: 'DECISION_HLS', response: hls },
  DECISION_UNSUPPORTED: { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() },
  PREPARING: { type: 'PREPARING', now: 0, estimatedSeconds: 30, retryAfterSeconds: 5 },
  PREPARE_TICK: { type: 'PREPARE_TICK', now: 0 },
  CANCEL: { type: 'CANCEL' },
  REQUEST_FAILED: { type: 'REQUEST_FAILED', error: otherError },
  CAN_PLAY: { type: 'CAN_PLAY' },
  PLAYING: { type: 'PLAYING' },
  PAUSE: { type: 'PAUSE' },
  PLAY: { type: 'PLAY', position: 10 },
  PLAY_BLOCKED: { type: 'PLAY_BLOCKED' },
  WAITING: { type: 'WAITING' },
  ENDED: { type: 'ENDED' },
  SWITCH_AUDIO: { type: 'SWITCH_AUDIO', audioStreamIndex: 2, t: 10 },
  MEDIA_ERROR: { type: 'MEDIA_ERROR', now: 0, source: 'hls', kind: 'network', position: 10 },
  PAUSE_RELEASE: { type: 'PAUSE_RELEASE' },
  RETRY: { type: 'RETRY', position: 10 },
  PLAY_FILE: { type: 'PLAY_FILE', fileId: 92, startPositionSeconds: 0 },
  NEXT: { type: 'NEXT' },
  NEXT_PART: { type: 'NEXT_PART' },
};

const LISTED: Record<PlayerStateName, PlayerEventType[]> = {
  idle: ['LOAD', 'ITEM_NOT_FOUND'],
  requesting: ['DECISION_DIRECT', 'DECISION_HLS', 'DECISION_UNSUPPORTED', 'PREPARING', 'REQUEST_FAILED', 'ITEM_NOT_FOUND'],
  preparing: ['PREPARE_TICK', 'CANCEL', 'ITEM_NOT_FOUND'],
  loading: ['CAN_PLAY', 'PLAYING', 'PAUSE', 'PLAY_BLOCKED', 'MEDIA_ERROR', 'ITEM_NOT_FOUND'],
  blocked: ['PLAYING', 'PAUSE_RELEASE', 'PLAY', 'MEDIA_ERROR', 'ITEM_NOT_FOUND'],
  playing: ['PAUSE', 'WAITING', 'ENDED', 'SWITCH_AUDIO', 'MEDIA_ERROR', 'ITEM_NOT_FOUND'],
  paused: ['PLAY', 'PLAYING', 'ENDED', 'PLAY_BLOCKED', 'PAUSE_RELEASE', 'SWITCH_AUDIO', 'MEDIA_ERROR', 'ITEM_NOT_FOUND'],
  buffering: ['PLAYING', 'PAUSE', 'ENDED', 'SWITCH_AUDIO', 'MEDIA_ERROR', 'ITEM_NOT_FOUND'],
  switching: ['DECISION_DIRECT', 'DECISION_HLS', 'DECISION_UNSUPPORTED', 'PREPARING', 'REQUEST_FAILED', 'ITEM_NOT_FOUND'],
  recovering: [
    'DECISION_DIRECT', 'DECISION_HLS', 'DECISION_UNSUPPORTED', 'PREPARING', 'REQUEST_FAILED', 'MEDIA_ERROR', 'ITEM_NOT_FOUND',
  ],
  ended: ['PLAY', 'PLAYING', 'PAUSE_RELEASE', 'NEXT', 'NEXT_PART', 'ITEM_NOT_FOUND'],
  unsupported: ['RETRY', 'PLAY_FILE', 'ITEM_NOT_FOUND'],
  error: ['RETRY', 'PLAY_FILE'],
  exited: [],
};

describe('playerReducer: unlisted pairs are no-ops', () => {
  it('covers every state and event type', () => {
    expect(Object.keys(SAMPLE_STATES).sort()).toEqual([...ALL_STATE_NAMES].sort());
    expect(Object.keys(SAMPLE_EVENTS).sort()).toEqual([...ALL_EVENT_TYPES].sort());
    for (const name of ALL_STATE_NAMES) expect(SAMPLE_STATES[name]().name).toBe(name);
  });

  const pairs: Array<[PlayerStateName, PlayerEventType]> = [];
  for (const name of ALL_STATE_NAMES) {
    for (const type of ALL_EVENT_TYPES) if (!LISTED[name].includes(type)) pairs.push([name, type]);
  }

  it.each(pairs)('%s + %s returns the same state object', (name, type) => {
    const state = SAMPLE_STATES[name]();
    expect(playerReducer(state, SAMPLE_EVENTS[type])).toBe(state);
  });

  it('every listed pair is exercised by a table row', () => {
    const covered = new Set(ROWS.map((r) => `${r.from().name}:${r.event.type}`));
    const missing: string[] = [];
    for (const name of ALL_STATE_NAMES) {
      for (const type of LISTED[name]) if (!covered.has(`${name}:${type}`)) missing.push(`${name}:${type}`);
    }
    expect(missing).toEqual([]);
  });
});

// --- Conditional no-ops inside listed pairs ------------------------------------------------

describe('playerReducer: guarded rows', () => {
  it('RETRY is ignored for kind version_failed', () => {
    const s = versionFailed();
    expect(playerReducer(s, { type: 'RETRY', position: 0 })).toBe(s);
  });

  it('PLAY in blocked without a release waits for the element (PLAYING)', () => {
    const s = blocked();
    expect(playerReducer(s, { type: 'PLAY', position: 0 })).toBe(s);
  });

  it('PLAYING cannot happen while released (no source attached)', () => {
    const paused = run(pausedHls(), { type: 'PAUSE_RELEASE' });
    expect(playerReducer(paused, { type: 'PLAYING' })).toBe(paused);
    const end = run(ended(), { type: 'PAUSE_RELEASE' });
    expect(playerReducer(end, { type: 'PLAYING' })).toBe(end);
  });

  it('SWITCH_AUDIO to the current track is a no-op', () => {
    const s = playingHls();
    expect(playerReducer(s, { type: 'SWITCH_AUDIO', audioStreamIndex: 1, t: 10 })).toBe(s);
  });

  it('PAUSE_RELEASE in direct mode is ignored (no session)', () => {
    const s = pausedDirect();
    expect(playerReducer(s, { type: 'PAUSE_RELEASE' })).toBe(s);
  });
});

// --- Context semantics ---------------------------------------------------------------------

describe('preparing budget (estimate-based cap)', () => {
  it.each([
    [30, 120],
    [600, 1200],
    [3600, 1800],
    [null, 120],
  ])('first estimate %s s → budget %s s', (estimate, budget) => {
    expect(prepareBudgetSeconds(estimate)).toBe(budget);
    expect(preparing(requesting(), estimate).prepareBudgetS).toBe(budget);
  });

  it('a later, larger estimate updates prepareEstimateS but not the budget', () => {
    let s = preparing(requesting(), 30);
    s = run(
      s,
      { type: 'PREPARE_TICK', now: 6_000 },
      { type: 'PREPARING', now: 7_000, estimatedSeconds: 900, retryAfterSeconds: 5 },
    );
    expect(s.name).toBe('preparing');
    expect(s).toMatchObject({ prepareStartedAt: 1_000, prepareBudgetS: 120, prepareEstimateS: 900 });
  });

  it('a decision resets the prepare fields', () => {
    const s = run(preparing(), { type: 'PREPARE_TICK', now: 2_000 }, { type: 'DECISION_HLS', response: hls });
    expect(s).toMatchObject({ prepareStartedAt: null, prepareBudgetS: null, prepareEstimateS: null });
  });

  it('RETRY after prepare_timeout starts a fresh wait', () => {
    let s = run(preparing(), { type: 'PREPARE_TICK', now: 200_000 });
    expect(s.error?.kind).toBe('prepare_timeout');
    s = run(s, { type: 'RETRY', position: 100 }, { type: 'PREPARING', now: 300_000, estimatedSeconds: 600, retryAfterSeconds: 5 });
    expect(s).toMatchObject({ prepareStartedAt: 300_000, prepareBudgetS: 1200 });
  });
});

describe('recovery window (lastRecoveryAt, 30 s)', () => {
  const fatal = (now: number): PlayerEvent => ({ type: 'MEDIA_ERROR', now, source: 'hls', kind: 'network', position: 400 });
  const recovered = (s: PlayerState) =>
    run(s, { type: 'DECISION_HLS', response: f.playbackHls({ session_id: 'sess-2' }) }, { type: 'PLAYING' });

  it('t=0 recovers, t=10 s fails, t=40 s recovers', () => {
    const first = run(playingHls(), fatal(0));
    expect(first.name).toBe('recovering');
    const back = recovered(first);
    expect(run(back, fatal(10_000)).name).toBe('error');
    expect(run(back, fatal(10_000)).error?.message).toBe('Error de reproducción: No se puede conectar con el servidor.');
    expect(run(back, fatal(40_000)).name).toBe('recovering');
  });

  it('recoveryAllowed', () => {
    expect(recoveryAllowed(playingHls(), 0)).toBe(true);
    const s = recovering(1_000);
    expect(recoveryAllowed(s, 30_999)).toBe(false);
    expect(recoveryAllowed(s, 31_000)).toBe(true);
  });

  it('RETRY and PLAY_FILE reset lastRecoveryAt and the prepare fields', () => {
    const err = run(recovered(recovering(0)), fatal(5_000));
    expect(err.lastRecoveryAt).toBe(0);
    const retried = run(err, { type: 'RETRY', position: 400 });
    expect(retried).toMatchObject({ lastRecoveryAt: null, prepareStartedAt: null, error: null });
    expect(run(err, { type: 'PLAY_FILE', fileId: 92, startPositionSeconds: 0 }).lastRecoveryAt).toBeNull();
  });
});

describe('context', () => {
  it('a new session resets sessionReleased', () => {
    const released = run(pausedHls(), { type: 'PAUSE_RELEASE' }, { type: 'PLAY', position: 400 });
    expect(released.sessionReleased).toBe(true);
    expect(run(released, { type: 'DECISION_HLS', response: hls }).sessionReleased).toBe(false);
  });

  it('PLAY_FILE clears hasPlayed (no report for a file that never played)', () => {
    const s = run(playingHls(), { type: 'MEDIA_ERROR', now: 0, source: 'hls', kind: 'decode', position: 1 });
    expect(s.hasPlayed).toBe(true);
    expect(run(s, { type: 'PLAY_FILE', fileId: 92, startPositionSeconds: 0 }).hasPlayed).toBe(false);
  });

  it('a recovery keeps the current audio track', () => {
    const switched = run(switchingHls(), {
      type: 'DECISION_HLS',
      response: f.playbackHls({ session_id: 'sess-2', audio_stream_index: 2 }),
    });
    const s = run(switched, { type: 'PLAYING' }, { type: 'MEDIA_ERROR', now: 0, source: 'hls', kind: 'network', position: 500 });
    expect(s.pendingRequest?.intent).toMatchObject({ audioStreamIndex: 2, startPositionSeconds: 500, previousSessionId: null });
  });

  it('the reporter runs only in playing, paused, buffering and ended', () => {
    const on = ALL_STATE_NAMES.filter(canReport);
    expect(on.sort()).toEqual(['buffering', 'ended', 'paused', 'playing']);
  });

  it('the audio menu is enabled only where SWITCH_AUDIO is listed', () => {
    expect(ALL_STATE_NAMES.filter(canSwitchAudio).sort()).toEqual(['buffering', 'paused', 'playing']);
  });

  it('notices carry an increasing sequence number', () => {
    const a = run(switchingDirect(), { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() });
    const b = run(
      a,
      { type: 'SWITCH_AUDIO', audioStreamIndex: 3, t: 10 },
      { type: 'DECISION_UNSUPPORTED', response: f.playbackUnsupported() },
    );
    expect(b.notice?.seq).toBeGreaterThan(a.notice?.seq ?? Infinity);
  });
});
