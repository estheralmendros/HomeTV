import { act, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useElementHeight } from './useElementHeight';

function Probe({ initial }: { initial: number }) {
  const [el, setEl] = useState<HTMLDivElement | null>(null);
  const height = useElementHeight(el);
  return (
    <div
      ref={(node) => {
        if (node) Object.defineProperty(node, 'clientHeight', { configurable: true, value: initial });
        setEl(node);
      }}
      data-testid="box"
    >
      {height}
    </div>
  );
}

const setHeight = (value: number) =>
  Object.defineProperty(screen.getByTestId('box'), 'clientHeight', { configurable: true, value });

describe('useElementHeight', () => {
  afterEach(() => {
    delete (globalThis as { ResizeObserver?: unknown }).ResizeObserver;
  });

  it('without ResizeObserver: clientHeight on mount and on window resize', () => {
    render(<Probe initial={540} />);
    expect(screen.getByTestId('box')).toHaveTextContent('540');
    setHeight(720);
    act(() => void window.dispatchEvent(new Event('resize')));
    expect(screen.getByTestId('box')).toHaveTextContent('720');
  });

  it('with ResizeObserver: follows the observed size and disconnects on unmount', () => {
    let callback: (() => void) | null = null;
    const disconnect = vi.fn();
    globalThis.ResizeObserver = class {
      constructor(cb: () => void) {
        callback = cb;
      }
      observe() {}
      unobserve() {}
      disconnect = disconnect;
    } as unknown as typeof ResizeObserver;
    const { unmount } = render(<Probe initial={300} />);
    expect(screen.getByTestId('box')).toHaveTextContent('300');
    setHeight(410);
    act(() => callback?.());
    expect(screen.getByTestId('box')).toHaveTextContent('410');
    unmount();
    expect(disconnect).toHaveBeenCalled();
  });

  it('null element: 0', () => {
    function Empty() {
      return <p>{useElementHeight(null)}</p>;
    }
    render(<Empty />);
    expect(screen.getByText('0')).toBeInTheDocument();
  });
});
