import { useCallback, useEffect, useRef, useState } from 'react';

export const CONTROLS_HIDE_MS = 3000;

/**
 * Controls auto-hide after 3 s without pointer/keyboard activity while `active` (playing); they
 * always show otherwise. `poke()` reports activity, `toggle()` is the touch tap.
 */
export function useAutoHide(active: boolean) {
  const [hidden, setHidden] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };

  const schedule = useCallback(() => {
    clear();
    if (active) timer.current = setTimeout(() => setHidden(true), CONTROLS_HIDE_MS);
  }, [active]);

  const poke = useCallback(() => {
    setHidden(false);
    schedule();
  }, [schedule]);

  const toggle = useCallback(() => {
    if (!active) return;
    if (hidden) {
      poke();
    } else {
      clear();
      setHidden(true);
    }
  }, [active, hidden, poke]);

  useEffect(() => {
    setHidden(false);
    schedule();
    return clear;
  }, [schedule]);

  // Keyboard activity anywhere (the shortcuts listen on window too).
  useEffect(() => {
    window.addEventListener('keydown', poke);
    return () => window.removeEventListener('keydown', poke);
  }, [poke]);

  return { visible: !active || !hidden, poke, toggle };
}
