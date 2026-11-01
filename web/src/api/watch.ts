import { apiFetch } from './client';
import type { ProgressReport, ProgressRequest, WatchedRequest, WatchedResponse } from './types';

/** `keepalive` for the unload reports (pagehide / visibilitychange→hidden). */
export const putProgress = (body: ProgressRequest, options: { keepalive?: boolean; signal?: AbortSignal } = {}) =>
  apiFetch<ProgressReport>('/api/progress', { method: 'PUT', body, ...options });

export const setWatched = (body: WatchedRequest) =>
  apiFetch<WatchedResponse>('/api/watched', { method: 'POST', body });
