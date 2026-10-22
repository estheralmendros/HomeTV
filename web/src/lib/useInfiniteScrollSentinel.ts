import { useEffect, useRef, useState } from 'react';

export interface SentinelOptions {
  onReach: () => void;
  /** Observe only while more pages can be loaded and none is loading. */
  enabled: boolean;
  /** Distance before the end at which `onReach` fires (default 600 px). */
  marginPx?: number;
}

/**
 * Callback ref for the element after the last grid item: calls `onReach` when it comes within
 * `marginPx` of the viewport. Without IntersectionObserver it does nothing (a visible
 * "Cargar más" button is the fallback).
 */
export function useInfiniteScrollSentinel({ onReach, enabled, marginPx = 600 }: SentinelOptions) {
  const onReachRef = useRef(onReach);
  onReachRef.current = onReach;
  const [element, setElement] = useState<Element | null>(null);

  useEffect(() => {
    if (!element || !enabled || typeof IntersectionObserver === 'undefined') return undefined;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) onReachRef.current();
      },
      { rootMargin: `0px 0px ${marginPx}px 0px` },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [element, enabled, marginPx]);

  return setElement;
}
