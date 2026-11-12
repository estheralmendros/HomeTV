// "Formato de los subtítulos" (prd2.md FR-W9, FR-W10) through the real player: menu entry, radio
// groups and keys, the clash rule, the live `::cue` rule and cue lines, the sample line, the save on
// close, and the re-render budget of the keyed PlayerPage.
import { act, fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { Me, SubtitleStyle } from '@/api/types';
import * as f from '../../tests/fixtures';
import { until } from '../../tests/flush';
import { fakeTextTrack, setActiveCues } from '../../tests/media';
import { API, apiError } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { answerPlayback, renderPlayer, type PlayerHarness } from '../../tests/player';
import { setViewportWidth } from '../../tests/viewport';
import { cueFontSize } from './subtitleStyle';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

const hookCalls = vi.hoisted(() => ({ count: 0 }));
vi.mock('@/player/usePlayerSubtitles', async (importOriginal) => {
  const real = await importOriginal<typeof import('./usePlayerSubtitles')>();
  return {
    ...real,
    usePlayerSubtitles: (...args: Parameters<typeof real.usePlayerSubtitles>) => {
      hookCalls.count += 1;
      return real.usePlayerSubtitles(...args);
    },
  };
});

const SUBS = [f.subtitleTrack({ id: 'emb-3', language: 'spa' })];

beforeEach(() => {
  answerPlayback(f.playbackDirect({ subtitle_tracks: SUBS }));
});

async function start(options: Parameters<typeof renderPlayer>[1] = {}) {
  const p = renderPlayer('/watch/film/12', options);
  await p.startDirect(0);
  return p;
}

function openPanel() {
  fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
  fireEvent.click(screen.getByRole('menuitem', { name: 'Formato de los subtítulos' }));
  return screen.getByRole('dialog', { name: 'Formato de los subtítulos' });
}

const group = (name: string) => screen.getByRole('radiogroup', { name });
const radio = (groupName: string, name: string | RegExp) => within(group(groupName)).getByRole('radio', { name });
const checked = (groupName: string) => within(group(groupName)).getAllByRole('radio').find((r) => r.getAttribute('aria-checked') === 'true');
const styleText = () => document.querySelector('style[data-subtitle-style]')?.textContent ?? '';
const key = (k: string) => fireEvent.keyDown(document.activeElement as Element, { key: k });

async function selectSpanish(p: PlayerHarness) {
  fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
  fireEvent.click(screen.getByRole('menuitemradio', { name: 'Español' }));
  await until(() => expect(p.video().querySelector('track')).not.toBeNull());
  return p.video().querySelector('track') as HTMLTrackElement;
}

describe('opening and the keyboard (W-AC-11)', () => {
  it('the entry opens a dialog with five radio groups; focus on the checked size; arrows, Home/End, Tab; Escape closes back on "Subtítulos"', async () => {
    await start();
    const dialog = openPanel();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole('radiogroup')).toHaveLength(5);
    for (const name of ['Tamaño', 'Color', 'Contorno', 'Color del contorno', 'Posición']) expect(group(name)).toBeInTheDocument();
    expect(within(group('Color')).getAllByRole('radio').map((r) => r.textContent)).toEqual([
      'Blanco', 'Amarillo', 'Verde', 'Cian', 'Magenta', 'Naranja',
    ]);
    expect(document.activeElement).toBe(radio('Tamaño', 'Mediano'));
    key('ArrowRight');
    expect(checked('Tamaño')).toHaveTextContent('Grande');
    expect(document.activeElement).toBe(radio('Tamaño', 'Grande'));
    key('Home');
    expect(checked('Tamaño')).toHaveTextContent('Pequeño');
    key('End');
    expect(checked('Tamaño')).toHaveTextContent('Grande');
    key('ArrowLeft');
    expect(checked('Tamaño')).toHaveTextContent('Mediano');
    // Roving tabindex: one tab stop per group.
    expect(within(group('Color')).getAllByRole('radio').map((r) => r.tabIndex)).toEqual([0, -1, -1, -1, -1, -1]);
    key('Escape');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Subtítulos' }));
  });

  it('handled keys never reach the player shortcuts (no seek on ←/→)', async () => {
    const p = await start();
    p.at(100);
    openPanel();
    key('ArrowRight');
    expect(p.video().currentTime).toBe(100);
  });

  it('Tab walks one stop per row (the checked radio), then [Cerrar]; Shift+Tab walks back', async () => {
    const p = await start();
    openPanel();
    expect(document.activeElement).toBe(radio('Tamaño', 'Mediano'));
    await p.user.tab();
    expect(document.activeElement).toBe(radio('Color', 'Blanco'));
    await p.user.tab();
    expect(document.activeElement).toBe(radio('Contorno', 'Contorno'));
    await p.user.tab();
    expect(document.activeElement).toBe(radio('Color del contorno', 'Negro'));
    await p.user.tab();
    expect(document.activeElement).toBe(radio('Posición', 'Baja'));
    await p.user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cerrar' }));
    await p.user.tab({ shift: true });
    await p.user.tab({ shift: true });
    expect(document.activeElement).toBe(radio('Color del contorno', 'Negro'));
  });

  it('[Cerrar] closes it; opening another menu closes it too', async () => {
    await start();
    openPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    openPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
    expect(screen.queryByRole('dialog', { name: 'Formato de los subtítulos' })).not.toBeInTheDocument();
  });
});

describe('live preview (W-AC-12)', () => {
  it('each change rewrites the ::cue rule at once', async () => {
    await start();
    expect(styleText()).toContain('color: #FFFFFF; font-size: calc(5vmin * 1);');
    expect(styleText()).toContain('background: transparent');
    openPanel();
    fireEvent.click(radio('Color', 'Amarillo'));
    expect(styleText()).toContain('color: #FFFF00');
    fireEvent.click(radio('Tamaño', 'Grande'));
    expect(styleText()).toContain('font-size: calc(5vmin * 1.3);');
    fireEvent.click(radio('Contorno', 'Sombra'));
    expect(styleText()).toContain('text-shadow: 0.07em 0.07em 0.07em #000000');
    fireEvent.click(radio('Color del contorno', 'Blanco'));
    expect(styleText()).toContain('0.07em 0.07em 0.07em #FFFFFF');
    fireEvent.click(radio('Contorno', 'Sin contorno'));
    expect(styleText()).toContain('text-shadow: none');
  });

  it('a Posición change moves auto cues to -4 / -5 at once; file-positioned cues keep their line', async () => {
    const p = await start();
    const el = await selectSpanish(p);
    const auto = { line: 'auto' as number | 'auto', snapToLines: true };
    const placed = { line: 2 as number | 'auto', snapToLines: true };
    fakeTextTrack(el).cues = [auto, placed];
    el.dispatchEvent(new Event('load'));
    expect(auto.line).toBe(-3);
    openPanel();
    fireEvent.click(radio('Posición', 'Media'));
    expect(auto.line).toBe(-4);
    fireEvent.click(radio('Posición', 'Alta'));
    expect(auto.line).toBe(-5);
    expect(placed.line).toBe(2);
  });
});

describe('the clash rule (W-AC-13)', () => {
  it('white edge colour is disabled with white text and skipped; "Sin contorno" disables the whole row; edge on again gives Negro', async () => {
    await start();
    openPanel();
    expect(radio('Color del contorno', 'Blanco')).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(radio('Color del contorno', 'Blanco'));
    expect(checked('Color del contorno')).toHaveTextContent('Negro');
    fireEvent.click(radio('Color del contorno', 'Gris oscuro'));
    radio('Color del contorno', 'Gris oscuro').focus();
    key('ArrowRight');
    expect(checked('Color del contorno')).toHaveTextContent('Gris oscuro');

    // Yellow text with a white edge: then white text is the clash.
    fireEvent.click(radio('Color', 'Amarillo'));
    fireEvent.click(radio('Color del contorno', 'Blanco'));
    expect(radio('Color', 'Blanco')).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(radio('Contorno', 'Sin contorno'));
    expect(group('Color del contorno')).toHaveAttribute('aria-disabled', 'true');
    for (const r of within(group('Color del contorno')).getAllByRole('radio')) expect(r).toBeDisabled();
    fireEvent.click(radio('Color', 'Blanco'));
    expect(checked('Color')).toHaveTextContent('Blanco');
    fireEvent.click(radio('Contorno', 'Contorno'));
    expect(checked('Color del contorno')).toHaveTextContent('Negro');
  });

  it('a stored white-on-white clash opens on the normalized look (Negro); closing without a change sends no PUT', async () => {
    const p = await start({ subtitleStyle: f.subtitleStyle({ color: 'white', edge_color: 'white', edge: 'outline' }) });
    openPanel();
    expect(checked('Color')).toHaveTextContent('Blanco');
    expect(checked('Color del contorno')).toHaveTextContent('Negro');
    for (const name of ['Color', 'Color del contorno']) expect(checked(name)).not.toHaveAttribute('aria-disabled');
    key('Escape');
    openPanel();
    fireEvent.click(radio('Tamaño', 'Grande'));
    key('Escape');
    await until(() => expect(p.rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1));
    await p.rec.settled();
    expect(p.rec.find('PUT', '/api/me/subtitle-style')[0]?.body).toEqual(
      f.subtitleStyle({ size: 'large', color: 'white', edge_color: 'black', edge: 'outline' }),
    );
  });
});

describe('the sample line (W-AC-14, W-AC-16)', () => {
  it('≥ 600 px: shown while open and no cue is active; hidden while a cue shows; top-right panel', async () => {
    const p = await start();
    const el = await selectSpanish(p);
    openPanel();
    expect(screen.getByRole('dialog')).toHaveAttribute('data-layout', 'panel');
    expect(screen.getByTestId('subtitle-style-sample')).toHaveTextContent('Así se verán los subtítulos');
    act(() => setActiveCues(el, [{ line: -3, snapToLines: true }]));
    expect(screen.queryByTestId('subtitle-style-sample')).not.toBeInTheDocument();
    act(() => setActiveCues(el, []));
    expect(screen.getByTestId('subtitle-style-sample')).toBeInTheDocument();
    fireEvent.click(radio('Posición', 'Alta'));
    expect(screen.getByTestId('subtitle-style-sample').style.bottom).toBe('20%');
  });

  it('≥ 600 px with subtitles Off (no shown track): the sample shows', async () => {
    const p = await start();
    expect(p.video().querySelector('track')).toBeNull();
    openPanel();
    expect(screen.getByRole('dialog')).toHaveAttribute('data-layout', 'panel');
    expect(screen.getByTestId('subtitle-style-sample')).toHaveTextContent('Así se verán los subtítulos');
  });

  it('< 600 px: a sheet; the sample always shows above it and moves with Posición', async () => {
    setViewportWidth(360);
    const p = await start();
    const el = await selectSpanish(p);
    openPanel();
    expect(screen.getByRole('dialog')).toHaveAttribute('data-layout', 'sheet');
    act(() => setActiveCues(el, [{ line: -3, snapToLines: true }]));
    const sample = screen.getByTestId('subtitle-style-sample');
    expect(sample.style.bottom).toContain('var(--style-sheet-h)');
    // The sheet sits on the control bar (bottom: var(--player-controls-h)), so the sample clears both.
    expect(sample.style.bottom).toContain('var(--player-controls-h)');
    expect(sample.style.bottom).not.toContain('0.06');
    fireEvent.click(radio('Posición', 'Media'));
    expect(screen.getByTestId('subtitle-style-sample').style.bottom).toContain('0.06');
  });

  it('the sample is drawn with the draft colour and the size of the cues', async () => {
    await start();
    openPanel();
    fireEvent.click(radio('Color', 'Cian'));
    fireEvent.click(radio('Tamaño', 'Pequeño'));
    const sample = screen.getByTestId('subtitle-style-sample');
    expect(sample.style.color).toBe('rgb(0, 255, 255)');
    // The same size as the live cues' `::cue` rule (the preview never differs from the applied look).
    const cues = document.createElement('p');
    cues.style.fontSize = cueFontSize('small'); // normalised the same way (jsdom folds the calc)
    expect(sample.style.fontSize).toBe(cues.style.fontSize);
    expect(styleText()).toContain('font-size: calc(5vmin * 0.8);');
    expect(sample).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('saving on close (W-AC-15)', () => {
  const styleOf = (qc: PlayerHarness['queryClient']) => qc.getQueryData<Me>(queryKeys.me())?.subtitle_style;

  it('closing it by opening another menu saves the change (one PUT)', async () => {
    const p = await start();
    openPanel();
    fireEvent.click(radio('Color', 'Amarillo'));
    fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
    expect(screen.queryByRole('dialog', { name: 'Formato de los subtítulos' })).not.toBeInTheDocument();
    await until(() => expect(p.rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1));
    await p.rec.settled();
    expect(p.rec.find('PUT', '/api/me/subtitle-style')[0]?.body).toEqual(f.subtitleStyle({ color: 'yellow' }));
    expect(styleOf(p.queryClient)?.color).toBe('yellow');
  });

  it('Escape after a change: one PUT with the full style; ["me"] holds it; no PUT without a change', async () => {
    const p = await start();
    openPanel();
    key('Escape');
    openPanel();
    fireEvent.click(radio('Color', 'Verde'));
    key('Escape');
    await until(() => expect(p.rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1));
    await p.rec.settled();
    const expected: SubtitleStyle = f.subtitleStyle({ color: 'green' });
    expect(p.rec.find('PUT', '/api/me/subtitle-style')[0]?.body).toEqual(expected);
    expect(styleOf(p.queryClient)).toEqual(expected);
    expect(styleText()).toContain('color: #00FF00');
  });

  it('a style save during the sign-in /api/me refetch is not reverted (cancelQueries)', async () => {
    let release: (() => void) | null = null;
    let answered = false;
    server.use(
      http.get(`${API}/me`, async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        answered = true;
        return HttpResponse.json(f.me());
      }),
    );
    const p = await start();
    void p.queryClient.invalidateQueries({ queryKey: queryKeys.me() });
    // The handler holds the answer (the request reached it before the save).
    await until(() => expect(release).not.toBeNull());
    expect(p.queryClient.getQueryState(queryKeys.me())?.fetchStatus).toBe('fetching');
    openPanel();
    fireEvent.click(radio('Color', 'Naranja'));
    key('Escape');
    // Cancelled at once: the refetch's answer can no longer be written into ['me'].
    expect(p.queryClient.getQueryState(queryKeys.me())?.fetchStatus).toBe('idle');
    (release as unknown as () => void)();
    await until(() => expect(answered).toBe(true));
    await until(() => expect(p.rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1));
    await p.rec.settled();
    expect(styleOf(p.queryClient)?.color).toBe('orange');
    expect(p.queryClient.getQueryState(queryKeys.me())?.fetchStatus).toBe('idle');
  });

  it('a failure toasts in the player while mounted and keeps the style for the session', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    server.use(http.put(`${API}/me/subtitle-style`, () => apiError(500, 'internal')));
    const p = await start();
    openPanel();
    fireEvent.click(radio('Color', 'Magenta'));
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar' }));
    expect(await screen.findByText('No se ha podido guardar el formato de los subtítulos.')).toBeInTheDocument();
    expect(styleOf(p.queryClient)?.color).toBe('magenta');
    expect(vi.mocked(console.warn).mock.calls.flat().join(' ')).toContain('[api] internal (500)');
  });

  it('leaving the player with the panel open saves, clears the draft and a failure goes to the app toast', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    let release: (() => void) | null = null;
    server.use(
      http.put(`${API}/me/subtitle-style`, async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return apiError(500, 'internal');
      }),
    );
    await start();
    openPanel();
    fireEvent.click(radio('Color', 'Amarillo'));
    fireEvent.click(radio('Tamaño', 'Grande'));
    fireEvent.click(screen.getAllByRole('button', { name: 'Volver' })[0] as HTMLElement);
    await until(() => expect(release).not.toBeNull());
    (release as unknown as () => void)();
    expect(await screen.findByText('No se ha podido guardar el formato de los subtítulos.')).toBeInTheDocument();
    expect(screen.queryByTestId('player-root')).not.toBeInTheDocument();
  });

  it('after the page unmounts (next episode) the shell <style> shows the saved style, no draft', async () => {
    const DURATION = 1320;
    const file = (id: number) => f.episodeFile({ id, duration_seconds: DURATION, subtitle_tracks: [] });
    server.use(
      http.get(`${API}/episodes/:id`, ({ params }) =>
        HttpResponse.json(f.episodeDetail({ id: Number(params.id), media_files: [file(Number(params.id) + 156)], next_episode_id: 346, progress: null })),
      ),
    );
    answerPlayback(f.playbackDirect({ duration_seconds: DURATION, subtitle_tracks: [] }));
    const p = renderPlayer('/watch/episode/345');
    await p.startDirect(0);
    p.at(DURATION - 10);
    await screen.findByRole('button', { name: 'Reproducir ahora' });
    openPanel();
    fireEvent.click(radio('Color', 'Cian'));
    expect(styleText()).toContain('#00FFFF');
    fireEvent.click(screen.getByRole('button', { name: 'Reproducir ahora' }));
    await until(() => expect(screen.queryByRole('dialog', { name: 'Formato de los subtítulos' })).not.toBeInTheDocument());
    // Saved (PUT 204): the saved style is now cian; a fresh panel opens on it with no draft left.
    await until(() => expect(p.rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1));
    expect(styleText()).toContain('#00FFFF');
    p.queryClient.setQueryData<Me>(queryKeys.me(), (me) => me && { ...me, subtitle_style: f.subtitleStyle() });
    await until(() => expect(styleText()).toContain('color: #FFFFFF'));
  });
});

describe('re-render budget (W-AC-18)', () => {
  it('size, colour and edge steps never call PlayerPage again; each Posición step calls it once', async () => {
    await start();
    openPanel();
    const before = hookCalls.count;
    fireEvent.click(radio('Tamaño', 'Grande'));
    fireEvent.click(radio('Color', 'Amarillo'));
    fireEvent.click(radio('Contorno', 'Sombra'));
    fireEvent.click(radio('Color del contorno', 'Gris oscuro'));
    expect(hookCalls.count).toBe(before);
    fireEvent.click(radio('Posición', 'Media'));
    expect(hookCalls.count).toBe(before + 1);
    fireEvent.click(radio('Posición', 'Alta'));
    expect(hookCalls.count).toBe(before + 2);
  });
});
