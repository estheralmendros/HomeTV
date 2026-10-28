import { act, render, screen } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useOverflows } from './useOverflows';

const observers: FakeObserver[] = [];

class FakeObserver {
  observed: Element[] = [];
  disconnect = vi.fn();
  constructor(public callback: () => void) {
    observers.push(this);
  }
  observe(el: Element) {
    this.observed.push(el);
  }
}

/** jsdom has no layout: stub the two heights the hook compares. */
function setHeights(el: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(el, 'scrollHeight', { configurable: true, value: scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, value: clientHeight });
}

let heights: [number, number] = [0, 0];

function Probe({ active, content = 'text' }: { active: boolean; content?: string }) {
  const ref = useRef<HTMLParagraphElement>(null);
  const overflows = useOverflows(ref, active, content);
  return (
    <p
      ref={(el) => {
        (ref as { current: HTMLParagraphElement | null }).current = el;
        if (el) setHeights(el, ...heights);
      }}
      data-testid="text"
    >
      {overflows ? 'overflows' : 'fits'}
    </p>
  );
}

afterEach(() => {
  observers.length = 0;
  vi.unstubAllGlobals();
});

describe('useOverflows', () => {
  it('is true when the content is taller than the box (beyond 1 px of rounding)', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    heights = [120, 96];
    render(<Probe active />);
    expect(screen.getByTestId('text')).toHaveTextContent('overflows');
  });

  it('is false when it fits, including a 1 px rounding difference', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    heights = [97, 96];
    render(<Probe active />);
    expect(screen.getByTestId('text')).toHaveTextContent('fits');
  });

  it('keeps the last measured value while inactive (expanded text stays "overflowing")', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    heights = [120, 96];
    const { rerender } = render(<Probe active />);
    const el = screen.getByTestId('text');
    expect(el).toHaveTextContent('overflows');
    heights = [120, 120];
    setHeights(el, 120, 120);
    rerender(<Probe active={false} />);
    expect(el).toHaveTextContent('overflows');
  });

  it('measures again on resize while active and disconnects when inactive or unmounted', () => {
    vi.stubGlobal('ResizeObserver', FakeObserver);
    heights = [60, 96];
    const { rerender, unmount } = render(<Probe active />);
    const el = screen.getByTestId('text');
    expect(el).toHaveTextContent('fits');
    expect(observers).toHaveLength(1);
    expect(observers[0]!.observed).toEqual([el]);
    setHeights(el, 200, 96);
    act(() => observers[0]!.callback());
    expect(el).toHaveTextContent('overflows');
    rerender(<Probe active={false} />);
    expect(observers[0]!.disconnect).toHaveBeenCalled();
    rerender(<Probe active />);
    expect(observers).toHaveLength(2);
    unmount();
    expect(observers[1]!.disconnect).toHaveBeenCalled();
  });

  it.each([
    ['without ResizeObserver', undefined],
    ['with a ResizeObserver that does not fire (the clamped box keeps its size)', FakeObserver],
  ])('measures again when the content changes while mounted (%s)', (_label, observer) => {
    vi.stubGlobal('ResizeObserver', observer);
    heights = [96, 96];
    const { rerender } = render(<Probe active content="short" />);
    const el = screen.getByTestId('text');
    expect(el).toHaveTextContent('fits');
    heights = [200, 96];
    rerender(<Probe active content="a much longer text" />);
    expect(el).toHaveTextContent('overflows');
  });

  it('works without ResizeObserver (jsdom, old browsers)', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    heights = [10, 96];
    expect(() => render(<Probe active />)).not.toThrow();
  });
});
