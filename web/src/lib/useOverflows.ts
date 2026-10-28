import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * Whether `ref`'s content is taller than its box (a line-clamped text that is cut off).
 * Measured in a layout effect while `active`, again whenever `content` changes (a refetch can
 * replace the text of a mounted element without resizing its clamped box), and on resize through
 * ResizeObserver when it exists (jsdom has none). While inactive (e.g. the text is expanded) the last measured value is
 * kept, so a "Leer menos" toggle stays visible.
 */
export function useOverflows(ref: RefObject<HTMLElement>, active: boolean, content?: unknown): boolean {
  const [overflows, setOverflows] = useState(false);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!active || !el) return;
    const measure = () => setOverflows(el.scrollHeight > el.clientHeight + 1);
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref, active, content]);
  return overflows;
}
