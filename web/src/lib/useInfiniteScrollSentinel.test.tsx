import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useInfiniteScrollSentinel } from './useInfiniteScrollSentinel';

type Callback = (entries: Array<{ isIntersecting: boolean }>) => void;
const observers: Array<{ callback: Callback; options: IntersectionObserverInit | undefined; disconnect: () => void; observed: Element[] }> = [];

class FakeObserver {
  observed: Element[] = [];
  disconnect = vi.fn();
  constructor(public callback: Callback, public options: IntersectionObserverInit | undefined = undefined) {
    observers.push(this);
  }
  observe(el: Element) {
    this.observed.push(el);
  }
}

function Sentinel({ onReach, enabled }: { onReach: () => void; enabled: boolean }) {
  const ref = useInfiniteScrollSentinel({ onReach, enabled });
  return <div ref={ref} data-testid="sentinel" />;
}

afterEach(() => {
  observers.length = 0;
  vi.unstubAllGlobals();
});

describe('useInfiniteScrollSentinel', () => {
  it('observes with a 600 px margin and calls onReach when intersecting', () => {
    vi.stubGlobal('IntersectionObserver', FakeObserver);
    const onReach = vi.fn();
    render(<Sentinel onReach={onReach} enabled />);
    expect(observers).toHaveLength(1);
    expect(observers[0]!.options?.rootMargin).toBe('0px 0px 600px 0px');
    observers[0]!.callback([{ isIntersecting: false }]);
    observers[0]!.callback([{ isIntersecting: true }]);
    expect(onReach).toHaveBeenCalledTimes(1);
  });

  it('does not observe while disabled and disconnects on unmount', () => {
    vi.stubGlobal('IntersectionObserver', FakeObserver);
    const { rerender, unmount } = render(<Sentinel onReach={vi.fn()} enabled={false} />);
    expect(observers).toHaveLength(0);
    rerender(<Sentinel onReach={vi.fn()} enabled />);
    expect(observers).toHaveLength(1);
    unmount();
    expect(observers[0]!.disconnect).toHaveBeenCalled();
  });

  it('is a no-op without IntersectionObserver (the "Cargar más" button is the fallback)', () => {
    vi.stubGlobal('IntersectionObserver', undefined);
    expect(() => render(<Sentinel onReach={vi.fn()} enabled />)).not.toThrow();
  });
});
