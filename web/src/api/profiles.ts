import { apiFetch } from './client';
import type { BootstrapRequest, Profile, ProfilesResponse, SessionResponse } from './types';

export const listProfiles = (signal?: AbortSignal) =>
  apiFetch<ProfilesResponse>('/api/profiles', { auth: false, signal });

export const bootstrap = (body: BootstrapRequest) =>
  apiFetch<SessionResponse>('/api/profiles/bootstrap', { method: 'POST', body, auth: false });

/** No body: no PIN for anyone. */
export const createSession = (profileId: number) =>
  apiFetch<SessionResponse>(`/api/profiles/${profileId}/session`, { method: 'POST', auth: false });

export const getMe = (signal?: AbortSignal) => apiFetch<Profile>('/api/me', { signal });
