import { fireEvent, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useKeyboardShortcuts, type ShortcutHandlers, type ShortcutOptions } from './useKeyboardShortcuts';

function setup(options: Partial<ShortcutOptions> = {}, overrides: Partial<ShortcutHandlers> = {}) {
  const handlers: ShortcutHandlers = {
    togglePlay: vi.fn(),
    seekBy: vi.fn(),
    changeVolume: vi.fn(),
    toggleFullscreen: vi.fn(),
    toggleMute: vi.fn(),
    closeMenu: vi.fn(),
    next: vi.fn(),
    activateBlocked: vi.fn(),
    ...overrides,
  };
  const opts: ShortcutOptions = { menuOpen: false, blocked: false, volumeEnabled: true, nextAvailable: false, ...options };
  const view = renderHook((props: ShortcutOptions) => useKeyboardShortcuts(handlers, props), { initialProps: opts });
  return { handlers, ...view };
}

const press = (key: string, target: Element = document.body, init: KeyboardEventInit = {}) =>
  fireEvent.keyDown(target, { key, ...init });

describe('useKeyboardShortcuts', () => {
  it('Space and K toggle play/pause and prevent the default', () => {
    const { handlers } = setup();
    expect(press(' ')).toBe(false); // defaultPrevented → fireEvent returns false
    press('k');
    press('K');
    expect(handlers.togglePlay).toHaveBeenCalledTimes(3);
  });

  it('←/→ seek ∓10 s, ↑/↓ change the volume by 10 %', () => {
    const { handlers } = setup();
    press('ArrowLeft');
    press('ArrowRight');
    press('ArrowUp');
    press('ArrowDown');
    expect(vi.mocked(handlers.seekBy).mock.calls).toEqual([[-10], [10]]);
    expect(vi.mocked(handlers.changeVolume).mock.calls).toEqual([[0.1], [-0.1]]);
  });

  it('↑/↓ do nothing on iOS (volume is read-only)', () => {
    const { handlers } = setup({ volumeEnabled: false });
    expect(press('ArrowUp')).toBe(true);
    press('ArrowDown');
    expect(handlers.changeVolume).not.toHaveBeenCalled();
  });

  it('F toggles fullscreen, M mutes', () => {
    const { handlers } = setup();
    press('f');
    press('M');
    expect(handlers.toggleFullscreen).toHaveBeenCalledTimes(1);
    expect(handlers.toggleMute).toHaveBeenCalledTimes(1);
  });

  it('N plays next only while its prompt is visible', () => {
    const { handlers, rerender } = setup();
    press('n');
    expect(handlers.next).not.toHaveBeenCalled();
    rerender({ menuOpen: false, blocked: false, volumeEnabled: true, nextAvailable: true });
    press('N');
    expect(handlers.next).toHaveBeenCalledTimes(1);
  });

  it('ignores keys while typing in inputs, selects, textareas and editable content', () => {
    const { handlers } = setup();
    for (const tag of ['input', 'select', 'textarea']) {
      const el = document.createElement(tag);
      document.body.appendChild(el);
      press(' ', el);
      press('f', el);
      el.remove();
    }
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    document.body.appendChild(editable);
    press('k', editable);
    expect(handlers.togglePlay).not.toHaveBeenCalled();
    expect(handlers.toggleFullscreen).not.toHaveBeenCalled();
  });

  it('with a menu open only Esc works (closes it)', () => {
    const { handlers } = setup({ menuOpen: true });
    press(' ');
    press('ArrowLeft');
    press('Escape');
    expect(handlers.togglePlay).not.toHaveBeenCalled();
    expect(handlers.seekBy).not.toHaveBeenCalled();
    expect(handlers.closeMenu).toHaveBeenCalledTimes(1);
  });

  it('Esc without a menu is left to the browser (fullscreen exit)', () => {
    const { handlers } = setup();
    expect(press('Escape')).toBe(true);
    expect(handlers.closeMenu).not.toHaveBeenCalled();
  });

  it('in blocked, Space/Enter/K activate "Toca para reproducir"', () => {
    const { handlers } = setup({ blocked: true });
    press(' ');
    press('Enter');
    press('k');
    expect(handlers.activateBlocked).toHaveBeenCalledTimes(3);
    expect(handlers.togglePlay).not.toHaveBeenCalled();
  });

  it('Space/Enter on a focused button are left to the button', () => {
    const { handlers } = setup({ blocked: true });
    const button = document.createElement('button');
    document.body.appendChild(button);
    expect(press(' ', button)).toBe(true);
    press('Enter', button);
    expect(handlers.activateBlocked).not.toHaveBeenCalled();
    press('k', button);
    expect(handlers.activateBlocked).toHaveBeenCalledTimes(1);
    button.remove();
  });

  it('ignores keys with Ctrl/Meta/Alt and stops when disabled or unmounted', () => {
    const { handlers, rerender, unmount } = setup();
    press('f', document.body, { ctrlKey: true });
    press('k', document.body, { metaKey: true });
    press('ArrowLeft', document.body, { altKey: true });
    expect(handlers.toggleFullscreen).not.toHaveBeenCalled();
    rerender({ menuOpen: false, blocked: false, volumeEnabled: true, nextAvailable: false, enabled: false });
    press('k');
    expect(handlers.togglePlay).not.toHaveBeenCalled();
    rerender({ menuOpen: false, blocked: false, volumeEnabled: true, nextAvailable: false });
    unmount();
    press('k');
    expect(handlers.togglePlay).not.toHaveBeenCalled();
  });

  it('uses the latest handlers without re-subscribing', () => {
    const first = vi.fn();
    const second = vi.fn();
    const opts: ShortcutOptions = { menuOpen: false, blocked: false, volumeEnabled: true, nextAvailable: false };
    const base = {
      seekBy: vi.fn(), changeVolume: vi.fn(), toggleFullscreen: vi.fn(), toggleMute: vi.fn(), closeMenu: vi.fn(),
    };
    const { rerender } = renderHook((h: ShortcutHandlers) => useKeyboardShortcuts(h, opts), {
      initialProps: { ...base, togglePlay: first },
    });
    rerender({ ...base, togglePlay: second });
    press('k');
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });
});
