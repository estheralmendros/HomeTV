import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { adminUnlock } from '@/auth/adminUnlock';
import { apiFetch, retryDelaySeconds, setTokenGetter, setUnauthorizedHandler } from './client';
import { ApiError } from './errors';
import { getScan } from './admin';
import { getInfo } from './system';
import { getMe } from './profiles';

let rec: ReturnType<typeof recordRequests>;
beforeEach(() => {
  rec = recordRequests();
});
afterEach(() => rec.dispose());

describe('apiFetch', () => {
  it('sends the Bearer token from the registered getter', async () => {
    setTokenGetter(() => 'tok-A');
    await getMe();
    expect(rec.calls[0]?.headers.get('authorization')).toBe('Bearer tok-A');
  });

  it('sends no Authorization header without a token or with auth=false', async () => {
    await getMe();
    setTokenGetter(() => 'tok-A');
    await apiFetch('/api/info', { auth: false });
    expect(rec.calls.map((c) => c.headers.get('authorization'))).toEqual([null, null]);
  });

  it('never reads the token from localStorage', async () => {
    localStorage.setItem('hometv.session', JSON.stringify({ token: 'stored', profile_id: 1, expires_at: '2099-01-01T00:00:00Z' }));
    await getMe();
    expect(rec.calls[0]?.headers.get('authorization')).toBeNull();
  });

  it('serializes query params, dropping undefined, null and empty strings', async () => {
    server.use(http.get('/api/films', () => HttpResponse.json({ items: [], page: 1, page_size: 60, total: 0 })));
    await apiFetch('/api/films', { query: { page: 2, q: '', genre: undefined, year_from: null, sort: 'year' } });
    expect(rec.calls[0]?.url.search).toBe('?page=2&sort=year');
  });

  it('sends JSON bodies with Content-Type', async () => {
    await apiFetch('/api/watched', { method: 'POST', body: { target: 'film', id: 12, watched: true } });
    await rec.settled();
    expect(rec.calls[0]?.headers.get('content-type')).toBe('application/json');
    expect(rec.calls[0]?.body).toEqual({ target: 'film', id: 12, watched: true });
  });

  it('returns undefined for 204', async () => {
    await expect(apiFetch('/api/hls/s1', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('parses the error envelope into ApiError with retryAfter from the header', async () => {
    server.use(
      http.post('/api/media-files/:id/playback', () =>
        apiError(503, 'preparing', { details: { retry_after_seconds: 7, estimated_seconds: 412 }, headers: { 'Retry-After': '5' }, message: 'Preparing video' }),
      ),
    );
    const err = await apiFetch('/api/media-files/91/playback', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 503, code: 'preparing', retryAfter: 5, message: 'Preparing video' });
    expect((err as ApiError).details).toEqual({ retry_after_seconds: 7, estimated_seconds: 412 });
  });

  it('falls back to details.retry_after_seconds when Retry-After is missing', async () => {
    server.use(http.get('/api/info', () => apiError(503, 'subtitle_pending', { details: { retry_after_seconds: 10 } })));
    await expect(getInfo()).rejects.toMatchObject({ retryAfter: 10 });
  });

  it('has retryAfter null when neither is present', async () => {
    server.use(http.get('/api/info', () => apiError(500, 'transcoder_failed')));
    await expect(getInfo()).rejects.toMatchObject({ retryAfter: null });
  });

  it('uses http_<status> when the body is not an envelope', async () => {
    server.use(http.get('/api/info', () => new HttpResponse('<html>bad gateway</html>', { status: 502 })));
    await expect(getInfo()).rejects.toMatchObject({ status: 502, code: 'http_502' });
  });

  it('turns a network failure into network_error (status 0)', async () => {
    server.use(http.get('/api/info', () => HttpResponse.error()));
    await expect(getInfo()).rejects.toMatchObject({ status: 0, code: 'network_error' });
  });

  it('rethrows aborts unchanged', async () => {
    const controller = new AbortController();
    controller.abort();
    const err = await apiFetch('/api/info', { signal: controller.signal }).catch((e: unknown) => e);
    expect(err).not.toBeInstanceOf(ApiError);
    expect((err as Error).name).toBe('AbortError');
  });

  it('reads text bodies with responseType text, with the same error parsing', async () => {
    setTokenGetter(() => 'tok-A');
    const text = await apiFetch<string>('/api/media-files/91/subtitles/emb-3.vtt', { responseType: 'text' });
    expect(text).toMatch(/^WEBVTT/);
    expect(rec.calls[0]?.headers.get('authorization')).toBe('Bearer tok-A');
    expect(rec.calls[0]?.url.search).toBe('');
    server.use(http.get('/api/media-files/:id/subtitles/:t', () => apiError(503, 'subtitle_pending', { headers: { 'Retry-After': '10' } })));
    await expect(apiFetch('/api/media-files/91/subtitles/emb-3.vtt', { responseType: 'text' })).rejects.toMatchObject({
      code: 'subtitle_pending',
      retryAfter: 10,
    });
  });

  it('passes keepalive to fetch', async () => {
    const spy = vi.spyOn(globalThis, 'fetch');
    await apiFetch('/api/hls/s1', { method: 'DELETE', keepalive: true });
    expect(spy.mock.calls[0]?.[1]).toMatchObject({ keepalive: true, method: 'DELETE' });
  });
});

describe('401 handling', () => {
  it('calls onUnauthorized on 401 unauthorized and rethrows', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    setTokenGetter(() => 'tok-A');
    server.use(http.get('/api/me', () => apiError(401, 'unauthorized')));
    await expect(getMe()).rejects.toMatchObject({ code: 'unauthorized' });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('does not call onUnauthorized for admin_locked / invalid_pin or auth=false calls', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    server.use(
      http.get('/api/admin/scan', () => apiError(401, 'admin_locked')),
      http.post('/api/admin/unlock', () => apiError(401, 'invalid_pin')),
      http.get('/api/info', () => apiError(401, 'unauthorized')),
    );
    await expect(getScan()).rejects.toMatchObject({ code: 'admin_locked' });
    await expect(apiFetch('/api/admin/unlock', { method: 'POST', body: { pin: '0000' } })).rejects.toMatchObject({ code: 'invalid_pin' });
    await expect(apiFetch('/api/info', { auth: false })).rejects.toMatchObject({ code: 'unauthorized' });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('clears the in-memory admin token on admin_locked', async () => {
    adminUnlock.set({ admin_token: 'adm', expires_at: '2099-01-01T00:00:00Z' });
    server.use(http.get('/api/admin/scan', () => apiError(401, 'admin_locked')));
    await expect(getScan()).rejects.toMatchObject({ code: 'admin_locked' });
    expect(adminUnlock.getAdminToken()).toBeNull();
  });
});

describe('X-Admin-Token', () => {
  it('is sent on admin calls only when held, never in the URL', async () => {
    setTokenGetter(() => 'tok-A');
    await getScan();
    adminUnlock.set({ admin_token: 'adm-1', expires_at: '2099-01-01T00:00:00Z' });
    await getScan();
    await getMe();
    expect(rec.calls.map((c) => c.headers.get('x-admin-token'))).toEqual([null, 'adm-1', null]);
    expect(rec.calls.every((c) => !c.url.search.includes('adm-1'))).toBe(true);
  });
});

describe('retryDelaySeconds', () => {
  const err = (retryAfter: number | null) => new ApiError({ status: 503, code: 'preparing', retryAfter });
  it('uses retryAfter, else the fallback, clamped to 1–60 s', () => {
    expect(retryDelaySeconds(err(5), 10)).toBe(5);
    expect(retryDelaySeconds(err(null), 10)).toBe(10);
    expect(retryDelaySeconds(err(0), 10)).toBe(1);
    expect(retryDelaySeconds(err(600), 10)).toBe(60);
    expect(retryDelaySeconds(err(null), 0)).toBe(1);
  });
});
