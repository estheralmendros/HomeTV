import { apiFetch } from './client';
import type { BootstrapRequest, Me, ProfilesResponse, SessionResponse, SubtitleStyle } from './types';

export const listProfiles = (signal?: AbortSignal) =>
  apiFetch<ProfilesResponse>('/api/profiles', { auth: false, signal });

export const bootstrap = (body: BootstrapRequest) =>
  apiFetch<SessionResponse>('/api/profiles/bootstrap', { method: 'POST', body, auth: false });

/** No body: no PIN for anyone. */
export const createSession = (profileId: number) =>
  apiFetch<SessionResponse>(`/api/profiles/${profileId}/session`, { method: 'POST', auth: false });

/** The token's profile + its subtitle style. */
export const getMe = (signal?: AbortSignal) => apiFetch<Me>('/api/me', { signal });

/** Replaces the profile's subtitle style (every key). */
export const putSubtitleStyle = (style: SubtitleStyle) => apiFetch<void>('/api/me/subtitle-style', { method: 'PUT', body: style });
