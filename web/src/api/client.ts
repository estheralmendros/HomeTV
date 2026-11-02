// apiFetch: same-origin `/api/...` calls with this tab's in-memory Bearer token.
import { adminUnlock } from '@/auth/adminUnlock';
import { ApiError } from './errors';

export type QueryValue = string | number | boolean | null | undefined;

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, QueryValue>;
  signal?: AbortSignal;
  keepalive?: boolean;
  /** Send the profile Bearer token (default true). */
  auth?: boolean;
  /** Send X-Admin-Token when one is held (every /api/admin/* call except unlock). */
  admin?: boolean;
  responseType?: 'json' | 'text';
}

type TokenGetter = () => string | null | undefined;

let tokenGetter: TokenGetter = () => null;
let onUnauthorized: (() => void) | null = null;

/** AuthProvider registers the getter of its in-memory session token. */
export function setTokenGetter(getter: TokenGetter): void {
  tokenGetter = getter;
}

/** AuthProvider registers the handler for 401 `unauthorized`. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

/** This tab's profile token (never read from localStorage). */
export function getToken(): string | null {
  return tokenGetter() ?? null;
}

/** Test helper: forget the registered getter and handler. */
export function resetApiClient(): void {
  tokenGetter = () => null;
  onUnauthorized = null;
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = new URL(path, window.location.origin);
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    url.searchParams.append(key, String(value));
  }
  return url.toString();
}

function parseRetryAfter(header: string | null, details: Record<string, unknown>): number | null {
  if (header !== null && header.trim() !== '') {
    const seconds = Number(header);
    if (Number.isFinite(seconds)) return seconds;
  }
  const fromDetails = details.retry_after_seconds;
  return typeof fromDetails === 'number' && Number.isFinite(fromDetails) ? fromDetails : null;
}

async function toApiError(response: Response): Promise<ApiError> {
  let code = `http_${response.status}`;
  let message: string | undefined;
  let details: Record<string, unknown> = {};
  try {
    const body: unknown = await response.json();
    const error = (body as { error?: unknown } | null)?.error as
      | { code?: unknown; message?: unknown; details?: unknown }
      | undefined;
    if (error && typeof error.code === 'string') {
      code = error.code;
      if (typeof error.message === 'string') message = error.message;
      if (error.details && typeof error.details === 'object') details = error.details as Record<string, unknown>;
    }
  } catch {
    // not an envelope
  }
  return new ApiError({
    status: response.status,
    code,
    message,
    details,
    retryAfter: parseRetryAfter(response.headers.get('Retry-After'), details),
  });
}

function isAbort(err: unknown): boolean {
  return (err as { name?: unknown } | null)?.name === 'AbortError';
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal, keepalive, auth = true, admin = false, responseType = 'json' } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = auth ? getToken() : null;
  if (token) headers.Authorization = `Bearer ${token}`;
  const adminToken = admin ? adminUnlock.getAdminToken() : null;
  if (adminToken) headers['X-Admin-Token'] = adminToken;

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
      keepalive,
    });
  } catch (err) {
    if (isAbort(err) || signal?.aborted) throw err;
    throw new ApiError({ status: 0, code: 'network_error', message: String(err) });
  }

  if (!response.ok) {
    const error = await toApiError(response);
    if (error.status === 401 && error.code === 'unauthorized' && auth) onUnauthorized?.();
    if (error.code === 'admin_locked') adminUnlock.clear();
    throw error;
  }
  if (response.status === 204) return undefined as T;
  if (responseType === 'text') return (await response.text()) as T;
  const text = await response.text();
  if (text === '') return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    // A 2xx that is not JSON (a proxy page, the SPA's index.html…).
    throw new ApiError({ status: response.status, code: 'invalid_response', message: 'Response body is not JSON' });
  }
}

/** Wait before a retry: `retryAfter ?? fallback`, clamped to 1–60 s. */
export function retryDelaySeconds(err: ApiError, fallback: number): number {
  const seconds = err.retryAfter ?? fallback;
  return Math.min(60, Math.max(1, seconds));
}
