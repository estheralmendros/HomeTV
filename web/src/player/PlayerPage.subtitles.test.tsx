// PlayerPage: subtitles — menu, Bearer fetch, blob <track> showing, the
// remembered language, subtitle_pending spinner then attach, terminal failures.
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { apiError } from '../../tests/msw/handlers';
import { fakeTextTrack } from '../../tests/media';
import { server } from '../../tests/msw/server';
import { SUBTITLE_CUE_LINE } from './tracks';
import { answerPlayback, renderPlayer, useFakePlayerTimers, type PlayerHarness } from '../../tests/player';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const SUBS = [
  f.subtitleTrack({ id: 'emb-3', language: 'spa' }),
  f.subtitleTrack({ id: 'ext-0', language: 'eng', source: 'external' }),
  f.subtitleTrack({ id: 'emb-5', kind: 'image', language: 'fre' }),
];
const SUB_URL = /\/api\/media-files\/91\/subtitles\//;

const trackEl = (p: PlayerHarness) => p.video().querySelector('track');

function openMenu() {
  fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
  return screen.getByRole('menu', { name: 'Subtítulos' });
}

describe('PlayerPage subtitles', () => {
  it('lists only text tracks; choosing one fetches the VTT with Bearer (no token) and shows it as a blob <track>; each pick saves the choice', async () => {
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    const menu = openMenu();
    expect(within(menu).getAllByRole('menuitemradio').map((i) => i.textContent)).toEqual([
      '✓ Desactivados',
      'Español',
      'Inglés · Externo',
    ]);
    expect(trackEl(p)).toBeNull();
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(trackEl(p)).not.toBeNull());
    const request = p.rec.find('GET', SUB_URL)[0];
    expect(request?.url.pathname).toBe('/api/media-files/91/subtitles/emb-3.vtt');
    expect(request?.url.search).toBe('');
    expect(request?.headers.get('authorization')).toBe('Bearer token-1');
    const el = trackEl(p) as HTMLTrackElement;
    expect(el.getAttribute('src')).toBe('blob:http://localhost:3000/mock-1');
    expect(el).toHaveAttribute('kind', 'subtitles');
    expect(el).toHaveAttribute('srclang', 'es');
    expect(el.track.mode).toBe('showing');
    await until(() => expect(p.rec.find('PUT', '/api/track-choices/films/12')).toHaveLength(1));
    expect(localStorage.getItem('hometv.subtitleLang.1')).toBeNull();

    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Desactivados' }));
    await until(() => expect(trackEl(p)).toBeNull());
    await until(() => expect(p.rec.find('PUT', '/api/track-choices/films/12')).toHaveLength(2));
    await p.rec.settled();
    expect(p.rec.find('PUT', '/api/track-choices/films/12').map((c) => (c.body as { subtitles: unknown }).subtitles)).toEqual([
      f.subtitleChoice(),
      { mode: 'off', language: null, title: null, forced: false, hearing_impaired: false, source: null, kind: null },
    ]);
  });

  // Firefox keeps painting the active cue of a showing <track> removed from the DOM (a frozen line after
  // "Desactivados"): the track must already be disabled when React takes the element out.
  it('a removed <track> (Off, or another track) is disabled before it leaves the <video>', async () => {
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    const video = p.video();
    const modesAtRemoval: string[] = [];
    const removeChild = video.removeChild.bind(video);
    vi.spyOn(video, 'removeChild').mockImplementation(<T extends Node>(child: T): T => {
      if (child instanceof HTMLTrackElement) modesAtRemoval.push(child.track.mode);
      return removeChild(child);
    });

    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(trackEl(p)?.track.mode).toBe('showing'));
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Inglés · Externo' }));
    await until(() => expect(trackEl(p)?.getAttribute('src')).toBe('blob:http://localhost:3000/mock-2'));
    await until(() => expect(trackEl(p)?.track.mode).toBe('showing'));
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Desactivados' }));
    await until(() => expect(trackEl(p)).toBeNull());

    expect(modesAtRemoval).toEqual(['disabled', 'disabled']);
  });

  it('lifts auto-positioned cues above the control bar once the <track> loads (positioned cues are kept)', async () => {
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(trackEl(p)).not.toBeNull());
    const el = trackEl(p) as HTMLTrackElement;
    const auto = { line: 'auto' as const, snapToLines: true };
    const placed = { line: 2, snapToLines: true };
    fakeTextTrack(el).cues = [auto, placed];
    el.dispatchEvent(new Event('load'));
    expect(auto.line).toBe(SUBTITLE_CUE_LINE);
    expect(SUBTITLE_CUE_LINE).toBeLessThan(-1);
    expect(placed.line).toBe(2);
  });

  it('the remembered subtitle part is the default and loads right after the decision, without delaying the video', async () => {
    server.use(
      http.get('/api/films/:id', () =>
        HttpResponse.json(f.filmDetail({ track_choice: f.trackChoice({ audio: null, subtitles: f.subtitleChoice({ language: 'eng', source: 'external' }) }) })),
      ),
    );
    let release: () => void = () => undefined;
    server.use(
      http.get('/api/media-files/:id/subtitles/:track', async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return new HttpResponse(f.WEBVTT, { headers: { 'Content-Type': 'text/vtt' } });
      }),
    );
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await until(() => expect(p.rec.find('GET', SUB_URL)).toHaveLength(1));
    expect(p.rec.find('GET', SUB_URL)[0]?.url.pathname).toBe('/api/media-files/91/subtitles/ext-0.vtt');
    await p.startDirect(0);
    release();
    await until(() => expect(trackEl(p)).not.toBeNull());
    expect((trackEl(p) as HTMLTrackElement).track.mode).toBe('showing');
  });

  it('503 subtitle_pending: a spinner and "Preparando…" in the menu, retried after Retry-After, then attached', async () => {
    useFakePlayerTimers();
    let calls = 0;
    server.use(
      http.get('/api/media-files/:id/subtitles/:track', () =>
        ++calls === 1
          ? apiError(503, 'subtitle_pending', { headers: { 'Retry-After': '10' } })
          : new HttpResponse(f.WEBVTT, { headers: { 'Content-Type': 'text/vtt' } }),
      ),
    );
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(p.rec.find('GET', SUB_URL)).toHaveLength(1));
    const menu = openMenu();
    await until(() => expect(within(menu).getByRole('menuitemradio', { name: /Español/ })).toHaveTextContent('Español · Preparando…'));
    expect(within(menu).getByRole('progressbar', { name: 'Preparando…' })).toBeInTheDocument();
    await advance(9_999);
    expect(p.rec.find('GET', SUB_URL)).toHaveLength(1);
    await advance(1);
    await until(() => expect(trackEl(p)).not.toBeNull());
    expect(p.rec.find('GET', SUB_URL)).toHaveLength(2);
    expect((trackEl(p) as HTMLTrackElement).track.mode).toBe('showing');
  });

  it.each([
    ['subtitle_extraction_failed', 500, 'No se han podido extraer estos subtítulos del archivo.'],
    ['subtitle_timeout', 504, 'Los subtítulos han tardado demasiado en convertirse.'],
  ])('%s: the toast once and Off, no retry', async (code, status, message) => {
    server.use(http.get('/api/media-files/:id/subtitles/:track', () => apiError(status, code)));
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(within(openMenu()).getByRole('menuitemradio', { name: 'Desactivados' })).toHaveAttribute('aria-checked', 'true');
    expect(p.rec.find('GET', SUB_URL)).toHaveLength(1);
    expect(trackEl(p)).toBeNull();
  });

  it('a malformed VTT (<track> error event) toasts and reverts to Off', async () => {
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(trackEl(p)).not.toBeNull());
    (trackEl(p) as HTMLTrackElement).dispatchEvent(new Event('error'));
    expect(await screen.findByText('No se han podido cargar los subtítulos.')).toBeInTheDocument();
    await until(() => expect(trackEl(p)).toBeNull());
  });

  it('an image-only file shows the image-subtitle note', async () => {
    answerPlayback(f.playbackDirect({ subtitle_tracks: [f.subtitleTrack({ id: 'emb-5', kind: 'image' })] }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    const menu = openMenu();
    expect(within(menu).getByText('No hay subtítulos disponibles')).toBeInTheDocument();
    expect(within(menu).getByText('Los subtítulos de imagen no se admiten en el navegador')).toBeInTheDocument();
  });

  it('an audio switch on the same file keeps the subtitle without refetching it', async () => {
    const tracks = [f.audioTrack({ index: 1 }), f.audioTrack({ index: 2, language: 'spa' })];
    server.use(http.get('/api/films/:id', () => HttpResponse.json(f.filmDetail({ media_files: [f.mediaFile({ audio_tracks: tracks })] }))));
    answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }), f.playbackDirect({ subtitle_tracks: SUBS, audio_stream_index: 2 }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(within(openMenu()).getByRole('menuitemradio', { name: 'Español' }));
    await until(() => expect(trackEl(p)).not.toBeNull());
    p.at(300);
    fireEvent.click(screen.getByRole('button', { name: 'Audio' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: /^Español/ }));
    await until(() => expect(p.posts()).toHaveLength(2));
    await p.startDirect(300);
    expect((trackEl(p) as HTMLTrackElement).getAttribute('src')).toBe('blob:http://localhost:3000/mock-1');
    expect(p.rec.find('GET', SUB_URL)).toHaveLength(1);
  });
});
