// Default MSW handlers: every endpoint the web uses, fixture-backed. Tests override single
// endpoints with `server.use(...)`. Unhandled requests fail the test (tests/setup.ts).
import { http, HttpResponse, type JsonBodyType } from 'msw';
import * as f from '../fixtures';

/** Path prefix for handlers (relative paths resolve against jsdom's location). */
export const API = '/api';

/** prd.md error envelope response. */
export function apiError(
  status: number,
  code: string,
  { details = {}, headers, message = code }: { details?: Record<string, unknown>; headers?: HeadersInit; message?: string } = {},
): HttpResponse<JsonBodyType> {
  return HttpResponse.json({ error: { code, message, details } } as JsonBodyType, { status, headers });
}

export const handlers = [
  // System & profiles
  http.get(`${API}/health`, () => HttpResponse.json({ status: 'ok' })),
  http.get(`${API}/info`, () => HttpResponse.json(f.info())),
  http.get(`${API}/profiles`, () =>
    HttpResponse.json({ items: [f.adminProfile({ id: 1, name: 'Esther' }), f.profile({ id: 2, name: 'Kids', avatar_id: 'avatar-04' })] }),
  ),
  http.post(`${API}/profiles/bootstrap`, () =>
    HttpResponse.json(f.sessionResponse(f.adminProfile()), { status: 201 }),
  ),
  http.post(`${API}/profiles/:id/session`, ({ params }) =>
    HttpResponse.json(f.sessionResponse(f.profile({ id: Number(params.id) }))),
  ),
  http.get(`${API}/me`, () => HttpResponse.json(f.profile())),

  // Catalog
  http.get(`${API}/home`, () => HttpResponse.json(f.homeResponse())),
  http.get(`${API}/films`, () => HttpResponse.json(f.page([f.filmSummary()]))),
  http.get(`${API}/films/:id`, ({ params }) => HttpResponse.json(f.filmDetail({ id: Number(params.id) }))),
  http.get(`${API}/shows`, () => HttpResponse.json(f.page([f.showSummary()]))),
  http.get(`${API}/shows/:id`, ({ params }) => HttpResponse.json(f.showDetail({ id: Number(params.id) }))),
  http.get(`${API}/shows/:id/seasons/:n`, ({ params }) =>
    HttpResponse.json(f.seasonDetail({ show_id: Number(params.id), season_number: Number(params.n) })),
  ),
  http.get(`${API}/episodes/:id`, ({ params }) => HttpResponse.json(f.episodeDetail({ id: Number(params.id) }))),
  http.get(`${API}/genres`, () => HttpResponse.json({ items: [f.genre(), f.genre({ id: 35, name: 'Comedia', count: 40 })] })),
  http.get(`${API}/search`, () => HttpResponse.json({ films: [f.filmSummary()], shows: [f.showSummary()] })),

  // Watch state
  http.put(`${API}/progress`, async ({ request }) => {
    const body = (await request.json()) as { media_file_id: number; position_seconds: number; duration_seconds?: number };
    return HttpResponse.json(
      f.progress({
        media_file_id: body.media_file_id,
        position_seconds: body.position_seconds,
        duration_seconds: body.duration_seconds ?? 9360,
      }),
    );
  }),
  http.post(`${API}/watched`, () => HttpResponse.json({ updated: 1 })),

  // Media & playback
  http.get(`${API}/media-files/:id`, ({ params }) => HttpResponse.json(f.mediaFile({ id: Number(params.id) }))),
  http.post(`${API}/media-files/:id/playback`, ({ params }) =>
    HttpResponse.json(f.playbackDirect({ url: `/api/media-files/${String(params.id)}/stream` })),
  ),
  http.get(`${API}/media-files/:id/subtitles/:track`, () =>
    new HttpResponse(f.WEBVTT, { headers: { 'Content-Type': 'text/vtt; charset=utf-8' } }),
  ),
  http.post(`${API}/hls/:sid/keepalive`, () => new HttpResponse(null, { status: 204 })),
  http.delete(`${API}/hls/:sid`, () => new HttpResponse(null, { status: 204 })),

  // Admin
  http.post(`${API}/admin/unlock`, () => HttpResponse.json(f.unlockResponse())),
  http.get(`${API}/admin/scan`, () => HttpResponse.json(f.scanStatus())),
  http.post(`${API}/admin/scan`, () =>
    HttpResponse.json(f.scanStatus({ state: 'running', phase: 'walking', finished_at: null }), { status: 202 }),
  ),
  http.get(`${API}/admin/unmatched`, () =>
    HttpResponse.json(f.page([f.unmatchedEntry(), f.unmatchedFileEntry()], { page_size: 50 })),
  ),
  http.get(`${API}/admin/tmdb/search`, () => HttpResponse.json({ results: [f.tmdbSearchResult()] })),
  http.get(`${API}/admin/tmdb/tv/:id/seasons`, () => HttpResponse.json(f.tmdbSeasons())),
  http.get(`${API}/admin/tmdb/tv/:id/seasons/:n`, ({ params }) =>
    HttpResponse.json(f.tmdbSeason({ season_number: Number(params.n) })),
  ),
  http.post(`${API}/admin/films/:id/match`, ({ params }) => HttpResponse.json(f.filmDetail({ id: Number(params.id) }))),
  http.post(`${API}/admin/shows/:id/match`, ({ params }) => HttpResponse.json(f.showDetail({ id: Number(params.id) }))),
  http.post(`${API}/admin/unmatched/:id/resolve`, () => HttpResponse.json({ target: { type: 'film', id: 12 } })),
  http.post(`${API}/admin/profiles`, async ({ request }) => {
    const body = (await request.json()) as { name: string; avatar_id: string; is_admin: boolean };
    return HttpResponse.json(f.profile({ id: 3, name: body.name, avatar_id: body.avatar_id, is_admin: body.is_admin }), {
      status: 201,
    });
  }),
  http.patch(`${API}/admin/profiles/:id`, async ({ params, request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json({ ...f.profile({ id: Number(params.id) }), ...body });
  }),
  http.delete(`${API}/admin/profiles/:id`, () => new HttpResponse(null, { status: 204 })),
];
