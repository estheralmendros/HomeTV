// PlayerPage: controls, keyboard shortcuts, volume persistence, fullscreen (desktop and iOS),
// auto-hide, the watched mark and the cross-tab notice (prd2 §2, §9 steps 11–12).
import { act, fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_KEY } from '@/auth/session';
import { setCapabilityEnv } from './capabilities';
import { iphoneEnv } from '../../tests/capsEnvs';
import * as f from '../../tests/fixtures';
import { advance, until } from '../../tests/flush';
import { API } from '../../tests/msw/handlers';
import { server } from '../../tests/msw/server';
import { renderPlayer, stubFullscreen, useFakePlayerTimers } from '../../tests/player';

vi.mock('hls.js', () => import('../../tests/fakeHls'));

let restore: (() => void) | null = null;
afterEach(() => {
  restore?.();
  restore = null;
  delete (HTMLVideoElement.prototype as { webkitEnterFullscreen?: unknown }).webkitEnterFullscreen;
});

const controls = () => screen.getByTestId('player-controls');

describe('controls', () => {
  it('have Spanish labels; play/pause, ∓10 s and the seek bar drive the element', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
    for (const name of ['Volver', 'Pausa', 'Retroceder 10 segundos', 'Avanzar 10 segundos', 'Silenciar', 'Subtítulos']) {
      expect(within(controls()).getByRole('button', { name })).toBeInTheDocument();
    }
    expect(within(controls()).getByRole('slider', { name: 'Volumen' })).toBeInTheDocument();
    expect(within(controls()).queryByRole('button', { name: 'Audio' })).not.toBeInTheDocument(); // 1 audio track
    expect(within(controls()).queryByRole('button', { name: 'Pantalla completa' })).not.toBeInTheDocument(); // no API in jsdom

    fireEvent.click(screen.getByRole('button', { name: 'Avanzar 10 segundos' }));
    expect(p.video().currentTime).toBe(110);
    fireEvent.click(screen.getByRole('button', { name: 'Retroceder 10 segundos' }));
    expect(p.video().currentTime).toBe(100);
    fireEvent.change(screen.getByRole('slider', { name: 'Posición' }), { target: { value: '600' } });
    expect(p.video().currentTime).toBe(600);
    // Dragging seeks once, on release (no ffmpeg restart per input event in HLS mode).
    const seek = screen.getByRole('slider', { name: 'Posición' });
    fireEvent.pointerDown(seek);
    fireEvent.change(seek, { target: { value: '900' } });
    fireEvent.change(seek, { target: { value: '1200' } });
    expect(p.video().currentTime).toBe(600);
    fireEvent.pointerUp(seek);
    expect(p.video().currentTime).toBe(1200);
    // Keyboard on the focused bar commits on keyup.
    fireEvent.keyDown(seek, { key: 'ArrowLeft' });
    fireEvent.change(seek, { target: { value: '600' } });
    expect(p.video().currentTime).toBe(1200);
    fireEvent.keyUp(seek, { key: 'ArrowLeft' });
    expect(p.video().currentTime).toBe(600);
    fireEvent.click(screen.getByRole('button', { name: 'Pausa' }));
    expect(pause).toHaveBeenCalled();
    p.fire('pause');
    expect(screen.getByRole('button', { name: 'Reproducir' })).toBeInTheDocument();
    p.fire('timeupdate');
    expect(screen.getByLabelText('Tiempo')).toHaveTextContent('0:10:00 / 2:36:00');
  });

  it('auto-hide after 3 s without activity while playing; always shown when paused', async () => {
    useFakePlayerTimers();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(controls()).toHaveAttribute('data-hidden', 'false');
    await advance(3_000);
    expect(controls()).toHaveAttribute('data-hidden', 'true');
    fireEvent.keyDown(screen.getByTestId('player-surface'), { key: 'Shift' });
    expect(controls()).toHaveAttribute('data-hidden', 'false');
    p.pauseAt(10);
    await advance(10_000);
    expect(controls()).toHaveAttribute('data-hidden', 'false');
  });

  it('shows ✓ Visto when a report answers watched=true', async () => {
    server.use(http.put(`${API}/progress`, () => HttpResponse.json(f.progress({ watched: true }))));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    p.pauseAt(9000);
    expect(await within(controls()).findByText('Visto')).toBeInTheDocument();
  });
});

describe('volume (hometv.volume)', () => {
  it('is restored at mount and persisted on change', async () => {
    localStorage.setItem('hometv.volume', JSON.stringify({ volume: 0.4, muted: true }));
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(p.video().volume).toBe(0.4);
    expect(p.video().muted).toBe(true);
    // prd2 §9 step 11 (Icons): the mute button draws an SVG speaker, never an emoji, in both states.
    const muteButton = screen.getByRole('button', { name: 'Activar sonido' });
    expect(muteButton.querySelector('svg')).not.toBeNull();
    expect(muteButton.textContent).not.toMatch(/\p{Emoji_Presentation}/u);
    fireEvent.click(muteButton);
    expect(p.video().muted).toBe(false);
    const soundButton = screen.getByRole('button', { name: 'Silenciar' });
    expect(soundButton.querySelector('svg')).not.toBeNull();
    expect(soundButton.textContent).not.toMatch(/\p{Emoji_Presentation}/u);
    fireEvent.change(screen.getByRole('slider', { name: 'Volumen' }), { target: { value: '0.75' } });
    expect(p.video().volume).toBe(0.75);
    expect(JSON.parse(localStorage.getItem('hometv.volume') ?? 'null')).toEqual({ volume: 0.75, muted: false });
  });
});

describe('keyboard shortcuts', () => {
  it('Space/K, ←/→, ↑/↓, F, M, Esc; ignored while typing in an input', async () => {
    restore = stubFullscreen();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const key = (k: string, target: Element = document.body) => fireEvent.keyDown(target, { key: k });
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');

    key(' ');
    expect(pause).toHaveBeenCalledTimes(1);
    p.fire('pause');
    key('k');
    expect(play).toHaveBeenCalledTimes(1);
    p.at(100, 'playing');

    key('ArrowRight');
    expect(p.video().currentTime).toBe(110);
    key('ArrowLeft');
    expect(p.video().currentTime).toBe(100);

    key('ArrowDown');
    expect(p.video().volume).toBeCloseTo(0.9);
    key('ArrowUp');
    expect(p.video().volume).toBeCloseTo(1);
    expect(JSON.parse(localStorage.getItem('hometv.volume') ?? 'null')).toEqual({ volume: 1, muted: false });

    key('m');
    expect(p.video().muted).toBe(true);
    key('m');
    expect(p.video().muted).toBe(false);

    key('f');
    expect(document.fullscreenElement).toBe(screen.getByTestId('player-root'));
    key('f');
    expect(document.fullscreenElement).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Subtítulos' }));
    expect(screen.getByRole('menu', { name: 'Subtítulos' })).toBeInTheDocument();
    key('ArrowRight');
    expect(p.video().currentTime).toBe(100); // a menu is open: only Esc
    key('Escape');
    expect(screen.queryByRole('menu', { name: 'Subtítulos' })).not.toBeInTheDocument();

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    key(' ', input);
    key('ArrowRight', input);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(p.video().currentTime).toBe(100);
    input.remove();
  });
});

describe('fullscreen', () => {
  it('desktop: the button requests fullscreen on the shell root and follows fullscreenchange', async () => {
    restore = stubFullscreen();
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    fireEvent.click(screen.getByRole('button', { name: 'Pantalla completa' }));
    expect(Element.prototype.requestFullscreen).toHaveBeenCalledTimes(1);
    expect(document.fullscreenElement).toBe(screen.getByTestId('player-root'));
    fireEvent.click(screen.getByRole('button', { name: 'Salir de pantalla completa' }));
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Pantalla completa' })).toBeInTheDocument();
  });

  it('iOS env: no volume slider (mute stays); fullscreen calls video.webkitEnterFullscreen when element fullscreen is unavailable; ↑/↓ do nothing', async () => {
    setCapabilityEnv(iphoneEnv());
    const enter = vi.fn();
    (HTMLVideoElement.prototype as { webkitEnterFullscreen?: () => void }).webkitEnterFullscreen = enter;
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(0);
    expect(screen.queryByRole('slider', { name: 'Volumen' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Silenciar' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Pantalla completa' }));
    expect(enter).toHaveBeenCalledTimes(1);
    expect(enter.mock.contexts[0]).toBe(p.video());
    fireEvent.keyDown(document.body, { key: 'ArrowDown' });
    expect(p.video().volume).toBe(1);
  });
});

describe('another tab switched profile', () => {
  it('shows the notice as a toast without interrupting playback; later reports keep this tab’s token', async () => {
    const p = renderPlayer('/watch/film/12');
    await p.startDirect(100);
    const other = JSON.stringify(f.session({ profile_id: 2, token: 'token-2' }));
    localStorage.setItem(SESSION_KEY, other);
    act(() => void window.dispatchEvent(new StorageEvent('storage', { key: SESSION_KEY, newValue: other })));
    await until(() =>
      expect(screen.getByText('El perfil ha cambiado a Kids en otra pestaña. Esta pestaña sigue con Ana: recarga para cambiar.')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Pausa' })).toBeInTheDocument();
    p.pauseAt(300);
    await until(() => expect(p.puts()).toHaveLength(1));
    expect(p.puts()[0]?.headers.get('authorization')).toBe('Bearer token-1');
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar notificación' }));
    expect(screen.queryByText(/El perfil ha cambiado/)).not.toBeInTheDocument();
  });
});
