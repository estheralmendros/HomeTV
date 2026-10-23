import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SeekBar } from './SeekBar';

describe('SeekBar', () => {
  it('is a range input labelled "Posición" with a spoken aria-valuetext', () => {
    render(<SeekBar current={751} duration={1320} buffered={[]} onSeek={vi.fn()} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    expect(slider).toHaveAttribute('type', 'range');
    expect(slider).toHaveAttribute('max', '1320');
    expect(slider).toHaveValue('751');
    expect(slider).toHaveAttribute('aria-valuetext', '12 minutos y 31 segundos de 22 minutos');
  });

  it('seeking calls onSeek with the new position', () => {
    const onSeek = vi.fn();
    render(<SeekBar current={0} duration={1320} buffered={[]} onSeek={onSeek} />);
    fireEvent.change(screen.getByRole('slider', { name: 'Posición' }), { target: { value: '600' } });
    expect(onSeek).toHaveBeenCalledWith(600);
  });

  it('dragging shows the dragged position but seeks once, on release', () => {
    const onSeek = vi.fn();
    const { rerender } = render(<SeekBar current={0} duration={1320} buffered={[]} onSeek={onSeek} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    fireEvent.pointerDown(slider, { pointerType: 'mouse' });
    fireEvent.change(slider, { target: { value: '600' } });
    fireEvent.change(slider, { target: { value: '751' } });
    expect(onSeek).not.toHaveBeenCalled();
    // The clock keeps ticking while dragging: the dragged value wins.
    rerender(<SeekBar current={3} duration={1320} buffered={[]} onSeek={onSeek} />);
    expect(slider).toHaveValue('751');
    expect(slider).toHaveAttribute('aria-valuetext', '12 minutos y 31 segundos de 22 minutos');
    // Released outside the bar (pointerup reaches the document).
    fireEvent.pointerUp(document.body, { pointerType: 'mouse' });
    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(onSeek).toHaveBeenCalledWith(751);
    rerender(<SeekBar current={751} duration={1320} buffered={[]} onSeek={onSeek} />);
    expect(slider).toHaveValue('751');
  });

  it('a press and release without moving does not seek', () => {
    const onSeek = vi.fn();
    render(<SeekBar current={10} duration={1320} buffered={[]} onSeek={onSeek} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    fireEvent.pointerDown(slider);
    fireEvent.pointerUp(slider);
    expect(onSeek).not.toHaveBeenCalled();
  });

  it('keyboard: a held arrow key commits once, on keyup', () => {
    const onSeek = vi.fn();
    render(<SeekBar current={600} duration={1320} buffered={[]} onSeek={onSeek} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    fireEvent.keyDown(slider, { key: 'ArrowRight' });
    fireEvent.change(slider, { target: { value: '601' } });
    fireEvent.keyDown(slider, { key: 'ArrowRight', repeat: true });
    fireEvent.change(slider, { target: { value: '602' } });
    expect(onSeek).not.toHaveBeenCalled();
    expect(slider).toHaveValue('602');
    fireEvent.keyUp(slider, { key: 'ArrowRight' });
    expect(onSeek).toHaveBeenCalledTimes(1);
    expect(onSeek).toHaveBeenCalledWith(602);
  });

  it('a pending drag is committed on blur', () => {
    const onSeek = vi.fn();
    render(<SeekBar current={0} duration={1320} buffered={[]} onSeek={onSeek} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    fireEvent.keyDown(slider, { key: 'End' });
    fireEvent.change(slider, { target: { value: '1320' } });
    fireEvent.blur(slider);
    expect(onSeek).toHaveBeenCalledWith(1320);
  });

  it('shows the buffered ranges', () => {
    render(<SeekBar current={0} duration={1000} buffered={[[0, 250]]} onSeek={vi.fn()} />);
    expect(screen.getByTestId('seek-buffered').getAttribute('style')).toContain('25%');
  });

  it('is disabled while the duration is unknown', () => {
    render(<SeekBar current={0} duration={null} buffered={[]} onSeek={vi.fn()} />);
    expect(screen.getByRole('slider', { name: 'Posición' })).toBeDisabled();
  });

  it('the hover tooltip uses the player clock format (always with hours, as "0:12:31 / 0:22:00")', () => {
    render(<SeekBar current={0} duration={1320} buffered={[]} onSeek={vi.fn()} />);
    const slider = screen.getByRole('slider', { name: 'Posición' });
    slider.getBoundingClientRect = () => ({ left: 0, width: 1320, top: 0, height: 10 }) as DOMRect;
    const move = new MouseEvent('pointermove', { bubbles: true, clientX: 751 });
    Object.defineProperty(move, 'pointerType', { value: 'mouse' });
    fireEvent(slider, move);
    expect(screen.getByText('0:12:31')).toBeInTheDocument();
  });
});
