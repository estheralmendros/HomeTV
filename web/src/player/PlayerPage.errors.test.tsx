// PlayerPage: request-time outcomes — preparing (texts, loop, cap, Cancel), busy, transcoder
// failures (detail, keyframe_index_failed terminal per version), item 404 and unsupported.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { FilmDetail } from '@/api/types';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, useFakePlayerTimers } from '../../tests/player';
import { createTestQueryClient, currentUrl } from '../../tests/renderWithProviders';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const HEVC = f.video({ codec: 'hevc', width: 3840, height: 2160 });

function serveFilm(film: FilmDetail = f.filmDetail()) {
  server.use(http.get(`${API}/films/:id`, () => HttpResponse.json(film)));
}

const preparing = (estimate?: number) =>
  apiError(503, 'preparing', { headers: { 'Retry-After': '5' }, details: estimate ? { estimated_seconds: estimate } : {} });

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

describe('503 preparing', () => {
  it('×2 (estimates 150 then 40) then 200: "quedan unos 3 min" → "queda menos de un minuto", three identical POSTs 5 s apart, then playback', async () => {
    useFakePlayerTimers();
    answerPlayback(preparing(150), preparing(40), f.playbackDirect());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(screen.getByText('Preparando el vídeo… quedan unos 3 min')).toBeInTheDocument());
    expect(screen.getByText('Se está analizando este archivo para reproducirlo.')).toBeInTheDocument();
    await advance(4_999);
    expect(p.posts()).toHaveLength(1);
    await advance(1);
    await until(() => expect(screen.getByText('Preparando el vídeo… queda menos de un minuto')).toBeInTheDocument());
    expect(p.posts()).toHaveLength(2);
    await advance(5_000);
    await until(() => expect(p.video().getAttribute('src')).toBe('/api/media-files/91/stream?token=token-1'));
    const bodies = await p.postBodies();
    expect(bodies).toHaveLength(3);
    expect(new Set(bodies.map((b) => JSON.stringify(b))).size).toBe(1);
    await p.startDirect(0);
    expect(screen.queryByText(/Preparando el vídeo/)).not.toBeInTheDocument();
  });

  it('cap: first estimate 90 s → the timeout screen once 180 s have elapsed, not before; Retry starts a new wait', async () => {
    useFakePlayerTimers();
    answerPlayback(() => preparing(90));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(screen.getByText('Preparando el vídeo… quedan unos 2 min')).toBeInTheDocument());
    for (let elapsed = 0; elapsed < 175_000; elapsed += 5_000) await advance(5_000);
    await until(() => expect(p.posts()).toHaveLength(36));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await advance(5_000);
    await until(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Este vídeo está tardando demasiado en prepararse. Vuelve a intentarlo en unos minutos.'),
    );
    expect(within(screen.getByRole('alert')).getByRole('button', { name: 'Volver' })).toBeInTheDocument();
    const count = p.posts().length;
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(count + 1));
    await until(() => expect(screen.getByText('Preparando el vídeo… quedan unos 2 min')).toBeInTheDocument());
  });

  it('[Cancelar] navigates back and stops the loop', async () => {
    useFakePlayerTimers();
    answerPlayback(() => preparing());
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(screen.getByText('Preparando el vídeo…')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    await until(() => expect(currentUrl(p.router)).toBe('/'));
    await advance(60_000);
    expect(p.posts()).toHaveLength(1);
  });
});

describe('busy (503 too_many_sessions / hls_disk_full)', () => {
  it.each([
    [
      'too_many_sessions',
      'El servidor ya está convirtiendo el número máximo de vídeos. Detén la reproducción en otro dispositivo y vuelve a intentarlo.',
    ],
    [
      'hls_disk_full',
      'El servidor no tiene espacio para convertir vídeo ahora mismo. Vuelve a intentarlo en unos minutos o detén la reproducción en otro dispositivo.',
    ],
  ])('%s: the busy screen with [Reintentar] [Volver]; Retry re-requests', async (code, message) => {
    answerPlayback(apiError(503, code), f.playbackDirect());
    const p = renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(message);
    expect(within(alert).getByRole('button', { name: 'Volver' })).toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startDirect(0);
  });
});

describe('500 transcoder_failed', () => {
  it('at request time: the catalog message, the detail in the collapsed "Detalles técnicos" block and [Reintentar]', async () => {
    answerPlayback(apiError(500, 'transcoder_failed', { details: { detail: 'ffmpeg exited with code 1' } }));
    renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('El servidor no ha podido preparar este archivo para reproducirlo.');
    const details = within(alert).getByText('Detalles técnicos').closest('details') as HTMLElement;
    expect(details).not.toHaveAttribute('open');
    expect(within(details).getByText('ffmpeg exited with code 1')).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });

  it('keyframe_index_failed: friendly message, no detail, no Retry; [Probar …] sends exactly one POST for the sibling; the failed file is never re-requested', async () => {
    serveFilm(f.filmDetail({ media_files: [f.mediaFile({ id: 91 }), f.mediaFile({ id: 92, label: '720p H264 · AAC 2.0', size_bytes: 4e9 })] }));
    answerPlayback((fileId) =>
      fileId === 91
        ? apiError(500, 'transcoder_failed', { details: { detail: 'keyframe_index_failed: stalled' } })
        : HttpResponse.json(f.playbackDirect({ url: '/api/media-files/92/stream' })),
    );
    const p = renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('El servidor no puede preparar esta versión para reproducirla.');
    expect(alert).not.toHaveTextContent('keyframe_index_failed');
    expect(within(alert).queryByText('Detalles técnicos')).not.toBeInTheDocument();
    expect(within(alert).queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: 'Probar 720p H264 · AAC 2.0 · 4 GB' }));
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startDirect(0, /\/api\/media-files\/92\/stream/);
    expect(p.posts().map((c) => c.url.pathname)).toEqual(['/api/media-files/91/playback', '/api/media-files/92/playback']);
  });

  it('keyframe_index_failed without another version of the same part: only [Volver]', async () => {
    serveFilm(f.filmDetail({ media_files: [f.mediaFile({ id: 91 }), f.mediaFile({ id: 92, video: HEVC })] }));
    answerPlayback(apiError(500, 'transcoder_failed', { details: { detail: 'keyframe_index_failed: stalled' } }));
    renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(within(alert).getAllByRole('button').map((b) => b.textContent)).toEqual(['Volver']);
  });
});

describe('item 404', () => {
  it('a film item load 404 → the gone screen, no POST, ["home"] invalidated; [Volver] goes back', async () => {
    server.use(http.get(`${API}/films/:id`, () => apiError(404, 'not_found')));
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.home(), f.homeResponse());
    const p = renderPlayer('/watch/film/12', { queryClient });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Esta película no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.');
    expect(p.posts()).toHaveLength(0);
    await until(() => expect(queryClient.getQueryState(queryKeys.home())?.isInvalidated).toBe(true));
    fireEvent.click(within(alert).getByRole('button', { name: 'Volver' }));
    await until(() => expect(currentUrl(p.router)).toBe('/'));
  });

  it('another item load failure without a home entry: an error with [Reintentar]', async () => {
    let calls = 0;
    server.use(http.get(`${API}/films/:id`, () => (++calls === 1 ? apiError(400, 'bad_request') : HttpResponse.json(f.filmDetail()))));
    const p = renderPlayer('/watch/film/12');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Se ha producido un error inesperado (bad_request).');
    fireEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    await until(() => expect(p.posts()).toHaveLength(1));
  });
});

describe('unsupported', () => {
  it('reason-specific message and caps-filtered alternatives of the same part; a choice re-requests that file', async () => {
    serveFilm(
      f.filmDetail({
        media_files: [
          f.mediaFile({ id: 90, video: HEVC, label: '2160p HEVC' }),
          f.mediaFile({ id: 91 }),
          f.mediaFile({ id: 93, probe_error: 'moov atom not found', label: 'Roto' }),
          f.mediaFile({ id: 94, video: HEVC, label: '1080p HEVC' }),
        ],
      }),
    );
    answerPlayback((fileId) =>
      fileId === 90 ? HttpResponse.json(f.playbackUnsupported()) : HttpResponse.json(f.playbackDirect({ url: '/api/media-files/91/stream' })),
    );
    const p = renderPlayer('/watch/film/12?file=90');
    expect(await screen.findByRole('heading', { name: /No se puede reproducir este vídeo en este navegador/ })).toBeInTheDocument();
    expect(screen.getByText(/no admite el códec de vídeo HEVC/)).toBeInTheDocument();
    const group = screen.getByRole('group', { name: 'Otras versiones' });
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['Probar 1080p H264 · AAC 2.0 · 23,5 GB']);
    fireEvent.click(within(group).getByRole('button'));
    await until(() => expect(p.posts()).toHaveLength(2));
    expect(p.posts()[1]?.url.pathname).toBe('/api/media-files/91/playback');
    await p.startDirect(0, /\/api\/media-files\/91\/stream/);
  });

  it.each([
    ['duration_unknown', 'Se desconoce su duración, así que solo se puede reproducir directamente'],
    ['keyframes_too_sparse', 'Sus fotogramas clave están demasiado separados para dividirlo en segmentos'],
  ])('%s copy', async (reason, body) => {
    answerPlayback(f.playbackUnsupported({ reason, detail: 'diagnostic' }));
    renderPlayer('/watch/film/12');
    expect(await screen.findByRole('heading', { name: /Este archivo no se puede convertir para reproducirlo/ })).toBeInTheDocument();
    expect(screen.getByText(new RegExp(body))).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Otras versiones' })).not.toBeInTheDocument();
  });
});
