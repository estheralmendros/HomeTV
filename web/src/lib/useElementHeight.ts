import { useEffect, useState } from 'react';

/**
 * The element's height in px, kept current by a ResizeObserver; without one (jsdom, old browsers)
 * `clientHeight` is read on mount and on window `resize`. 0 for no element.
 */
export function useElementHeight(el: Element | null): number {
  const [height, setHeight] = useState(0);
  useEffect(() => {
    if (!el) {
      setHeight(0);
      return;
    }
    const read = () => setHeight(el.clientHeight);
    read();
    if (typeof ResizeObserver === 'function') {
      const observer = new ResizeObserver(read);
      observer.observe(el);
      return () => observer.disconnect();
    }
    window.addEventListener('resize', read);
    return () => window.removeEventListener('resize', read);
  }, [el]);
  return height;
}
