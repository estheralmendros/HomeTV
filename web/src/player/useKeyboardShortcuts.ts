// Player keyboard shortcuts: Space/K play-pause, ←/→ ∓10 s, ↑/↓ volume ±10 %,
// F fullscreen, M mute, Esc closes an open menu (the browser handles the fullscreen exit), N next
// episode/part while its prompt is visible. In `blocked`, Space/Enter/K activate "Toca para
// reproducir" (synchronously, inside the key gesture). Ignored while typing or with a menu open,
// except the media keys (MediaPlayPause toggles, MediaPlay / MediaPause).
import { useEffect, useRef } from 'react';

export interface ShortcutHandlers {
  togglePlay: () => void;
  seekBy: (deltaSeconds: number) => void;
  changeVolume: (delta: number) => void;
  toggleFullscreen: () => void;
  toggleMute: () => void;
  closeMenu: () => void;
  next?: () => void;
  activateBlocked?: () => void;
  /** MediaPlay / MediaPause (MediaPlayPause uses togglePlay). */
  play?: () => void;
  pause?: () => void;
}

export interface ShortcutOptions {
  enabled?: boolean;
  menuOpen: boolean;
  blocked: boolean;
  /** False on iOS/iPadOS (volume is read-only): ↑/↓ do nothing. */
  volumeEnabled: boolean;
  /** A next-episode/next-part prompt is visible. */
  nextAvailable: boolean;
  /**
   * Handle the MediaPlayPause / MediaPlay / MediaPause keys (default true). False when the Media Session
   * API is present: its handlers are then the only path, so one press never toggles twice.
   */
  mediaKeys?: boolean;
}

export const SEEK_STEP_SECONDS = 10;
export const VOLUME_STEP = 0.1;

const MEDIA_KEYS: ReadonlySet<string> = new Set(['MediaPlayPause', 'MediaPlay', 'MediaPause']);

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  return target.closest('input, select, textarea, [contenteditable]:not([contenteditable="false"])') !== null;
}

/** Space/Enter on these elements activate them natively; don't handle them twice. */
function activatesNatively(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest('button, a[href], [role="button"], [role="menuitem"], [role="menuitemradio"]') !== null;
}

export function useKeyboardShortcuts(handlers: ShortcutHandlers, options: ShortcutOptions): void {
  const latest = useRef({ handlers, options });
  latest.current = { handlers, options };
  const enabled = options.enabled ?? true;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
      const { handlers: h, options: o } = latest.current;
      if (MEDIA_KEYS.has(event.key)) {
        if (o.mediaKeys === false) return;
        if (o.blocked && event.key !== 'MediaPause') h.activateBlocked?.();
        else if (event.key === 'MediaPlay') h.play?.();
        else if (event.key === 'MediaPause') h.pause?.();
        else h.togglePlay();
        event.preventDefault();
        return;
      }
      if (isTyping(event.target)) return;
      const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
      const native = (key === ' ' || key === 'Enter') && activatesNatively(event.target);

      let handled = true;
      if (o.menuOpen) {
        if (key === 'Escape') h.closeMenu();
        else handled = false;
      } else if (o.blocked && (key === ' ' || key === 'Enter' || key === 'k')) {
        if (native) handled = false;
        else h.activateBlocked?.();
      } else {
        switch (key) {
          case ' ':
          case 'k':
            if (native && key === ' ') handled = false;
            else h.togglePlay();
            break;
          case 'ArrowLeft':
            h.seekBy(-SEEK_STEP_SECONDS);
            break;
          case 'ArrowRight':
            h.seekBy(SEEK_STEP_SECONDS);
            break;
          case 'ArrowUp':
          case 'ArrowDown':
            if (o.volumeEnabled) h.changeVolume(key === 'ArrowUp' ? VOLUME_STEP : -VOLUME_STEP);
            else handled = false;
            break;
          case 'f':
            h.toggleFullscreen();
            break;
          case 'm':
            h.toggleMute();
            break;
          case 'n':
            if (o.nextAvailable && h.next) h.next();
            else handled = false;
            break;
          default:
            handled = false;
        }
      }
      if (handled) event.preventDefault();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
