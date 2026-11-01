import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { StarDisplay, StarInput } from './StarRating';

const fillsOf = (el: HTMLElement) => [...el.querySelectorAll('svg')].map((svg) => svg.getAttribute('data-fill'));

describe('StarDisplay', () => {
  it.each<[number, string, string[]]>([
    [4.5, '4,5 estrellas', ['full', 'full', 'full', 'full', 'half']],
    [1, '1 estrella', ['full', 'empty', 'empty', 'empty', 'empty']],
    [0.5, '0,5 estrellas', ['half', 'empty', 'empty', 'empty', 'empty']],
    [5, '5 estrellas', ['full', 'full', 'full', 'full', 'full']],
  ])('%s: five stars with the fills and the text equivalent "%s"', (value, name, fills) => {
    render(<StarDisplay value={value} />);
    const img = screen.getByRole('img', { name });
    expect(fillsOf(img)).toEqual(fills);
    for (const svg of img.querySelectorAll('svg')) expect(svg).toHaveAttribute('aria-hidden', 'true');
  });

  it('takes an explicit label (household average)', () => {
    render(<StarDisplay value={4} label="Media de la familia: 4,0 estrellas, 3 puntuaciones" size="md" />);
    expect(screen.getByRole('img', { name: 'Media de la familia: 4,0 estrellas, 3 puntuaciones' })).toBeInTheDocument();
  });

  it('draws the half star with a clipPath on the filled shape (inline SVG, no glyphs)', () => {
    render(<StarDisplay value={0.5} />);
    const half = screen.getByRole('img').querySelector('svg[data-fill="half"]')!;
    expect(half.getAttribute('viewBox')).toBe('0 0 24 24');
    const clip = half.querySelector('clipPath');
    expect(clip).not.toBeNull();
    expect(half.querySelector(`[clip-path="url(#${clip!.id})"]`)).not.toBeNull();
    expect(screen.getByRole('img').textContent).toBe('');
  });
});

function Controlled({ initial = null, onEnter, disabled }: { initial?: number | null; onEnter?: () => void; disabled?: boolean }) {
  const [value, setValue] = useState<number | null>(initial);
  return <StarInput value={value} onChange={setValue} onEnter={onEnter} disabled={disabled} />;
}

const slider = () => screen.getByRole('slider', { name: 'Puntuación' });

describe('StarInput', () => {
  it('is a focusable slider from ½ to 5; empty has no valuenow and "Sin puntuación"', () => {
    render(<Controlled />);
    expect(slider()).toHaveAttribute('tabindex', '0');
    expect(slider()).toHaveAttribute('aria-valuemin', '0.5');
    expect(slider()).toHaveAttribute('aria-valuemax', '5');
    expect(slider()).not.toHaveAttribute('aria-valuenow');
    expect(slider()).toHaveAttribute('aria-valuetext', 'Sin puntuación');
    expect(fillsOf(slider())).toEqual(['empty', 'empty', 'empty', 'empty', 'empty']);
  });

  it('→ from empty gives ½; → nine times gives 4,5 (US-2); ← / ↓ / ↑ step by ½ within ½…5', () => {
    render(<Controlled />);
    fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    expect(slider()).toHaveAttribute('aria-valuenow', '0.5');
    expect(slider()).toHaveAttribute('aria-valuetext', '0,5 estrellas');
    for (let i = 0; i < 8; i += 1) fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    expect(slider()).toHaveAttribute('aria-valuetext', '4,5 estrellas');
    fireEvent.keyDown(slider(), { key: 'ArrowUp' });
    fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    expect(slider()).toHaveAttribute('aria-valuenow', '5');
    fireEvent.keyDown(slider(), { key: 'ArrowDown' });
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' });
    expect(slider()).toHaveAttribute('aria-valuetext', '4 estrellas');
    fireEvent.keyDown(slider(), { key: 'Home' });
    expect(slider()).toHaveAttribute('aria-valuenow', '0.5');
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' });
    expect(slider()).toHaveAttribute('aria-valuenow', '0.5');
    fireEvent.keyDown(slider(), { key: 'End' });
    expect(slider()).toHaveAttribute('aria-valuenow', '5');
  });

  it('← from empty also gives ½', () => {
    render(<Controlled />);
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' });
    expect(slider()).toHaveAttribute('aria-valuenow', '0.5');
  });

  it('Enter calls onEnter only with a value and not disabled', () => {
    const onEnter = vi.fn();
    const { unmount } = render(<Controlled onEnter={onEnter} />);
    fireEvent.keyDown(slider(), { key: 'Enter' });
    expect(onEnter).not.toHaveBeenCalled();
    fireEvent.keyDown(slider(), { key: 'End' });
    fireEvent.keyDown(slider(), { key: 'Enter' });
    expect(onEnter).toHaveBeenCalledTimes(1);
    unmount();
    render(<Controlled initial={3} onEnter={onEnter} disabled />);
    fireEvent.keyDown(slider(), { key: 'Enter' });
    fireEvent.keyDown(slider(), { key: 'ArrowRight' });
    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(slider()).toHaveAttribute('aria-valuenow', '3');
    expect(slider()).toHaveAttribute('aria-disabled', 'true');
  });

  it('every handled key (Enter too, even when it does nothing) is prevented and never reaches window; Space is not handled', () => {
    const onWindowKey = vi.fn();
    window.addEventListener('keydown', onWindowKey);
    render(<Controlled />);
    for (const key of ['Enter', 'ArrowRight', 'ArrowLeft', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'Enter']) {
      expect(fireEvent.keyDown(slider(), { key }), key).toBe(false);
    }
    expect(onWindowKey).not.toHaveBeenCalled();
    expect(fireEvent.keyDown(slider(), { key: ' ' })).toBe(true);
    expect(onWindowKey).toHaveBeenCalledTimes(1);
    window.removeEventListener('keydown', onWindowKey);
  });

  function pointer(type: string, target: Element, clientX: number, pointerType = 'mouse') {
    const event = new MouseEvent(type, { bubbles: true, clientX });
    Object.defineProperty(event, 'pointerType', { value: pointerType });
    fireEvent(target, event);
  }

  function stars(): HTMLElement[] {
    const hits = [...slider().querySelectorAll<HTMLElement>('[data-star]')];
    hits.forEach((hit, i) => {
      hit.getBoundingClientRect = () => ({ left: i * 40, width: 40, top: 0, height: 48 }) as DOMRect;
    });
    return hits;
  }

  it('a click / tap on the left half of star 4 gives 3,5, on the right half 4 (mouse, touch, pen)', () => {
    render(<Controlled />);
    const star4 = stars()[3]!;
    pointer('pointerdown', star4, 125, 'touch');
    expect(slider()).toHaveAttribute('aria-valuetext', '3,5 estrellas');
    pointer('pointerdown', star4, 150, 'pen');
    expect(slider()).toHaveAttribute('aria-valuetext', '4 estrellas');
    pointer('pointerdown', stars()[0]!, 5, 'mouse');
    expect(slider()).toHaveAttribute('aria-valuenow', '0.5');
  });

  it('mouse hover previews the value without changing it; leaving restores it; touch never previews', () => {
    render(<Controlled initial={1} />);
    const [, , star3] = stars();
    pointer('pointermove', star3!, 100);
    expect(fillsOf(slider())).toEqual(['full', 'full', 'full', 'empty', 'empty']);
    expect(slider()).toHaveAttribute('aria-valuenow', '1');
    fireEvent.pointerLeave(slider());
    expect(fillsOf(slider())).toEqual(['full', 'empty', 'empty', 'empty', 'empty']);
    pointer('pointermove', star3!, 100, 'touch');
    expect(fillsOf(slider())).toEqual(['full', 'empty', 'empty', 'empty', 'empty']);
  });

  it('disabled: pointer does nothing and it leaves the tab order', () => {
    render(<Controlled initial={2} disabled />);
    pointer('pointerdown', stars()[4]!, 199);
    expect(slider()).toHaveAttribute('aria-valuenow', '2');
    expect(slider()).toHaveAttribute('tabindex', '-1');
  });
});
