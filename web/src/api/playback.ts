import { apiFetch } from './client';
import type { MediaFile, PlaybackRequest, PlaybackResponse } from './types';

export const getMediaFile = (id: number, signal?: AbortSignal) =>
  apiFetch<MediaFile>(`/api/media-files/${id}`, { signal });

export const requestPlayback = (fileId: number, body: PlaybackRequest, signal?: AbortSignal) =>
  apiFetch<PlaybackResponse>(`/api/media-files/${fileId}/playback`, { method: 'POST', body, signal });

/** HLS endpoints are authorized by the session id; the Bearer header is harmless. */
export const keepAliveHls = (sessionId: string) =>
  apiFetch<void>(`/api/hls/${encodeURIComponent(sessionId)}/keepalive`, { method: 'POST' });

export const deleteHlsSession = (sessionId: string, options: { keepalive?: boolean } = {}) =>
  apiFetch<void>(`/api/hls/${encodeURIComponent(sessionId)}`, { method: 'DELETE', ...options });

/** WebVTT text of a subtitle track URL (Bearer header, no ?token=; prd2 §9 step 6). */
export const getSubtitleText = (url: string, signal?: AbortSignal) =>
  apiFetch<string>(url, { responseType: 'text', signal });
