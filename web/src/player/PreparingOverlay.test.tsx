import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PreparingOverlay, preparingText } from './PreparingOverlay';

afterEach(() => vi.useRealTimers());

describe('preparingText', () => {
  it.each([
    [150, 'Preparando el vídeo… quedan unos 3 min'],
    [60, 'Preparando el vídeo… queda alrededor de 1 min'],
    [40, 'Preparando el vídeo… queda menos de un minuto'],
    [null, 'Preparando el vídeo…'],
  ])('estimate %s → %s', (estimate, text) => {
    expect(preparingText(estimate, false)).toBe(text);
  });

  it('an audio switch reads "Cambiando el audio… preparando el vídeo"', () => {
    expect(preparingText(150, true)).toBe('Cambiando el audio… preparando el vídeo');
  });
});

describe('PreparingOverlay', () => {
  it('shows the text, the secondary line, an elapsed counter and [Cancelar]', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    const start = Date.now();
    const onCancel = vi.fn();
    render(<PreparingOverlay estimateSeconds={150} startedAt={start} audioSwitch={false} onCancel={onCancel} />);
    expect(screen.getByText('Preparando el vídeo… quedan unos 3 min')).toBeInTheDocument();
    expect(screen.getByText('Se está analizando este archivo para reproducirlo.')).toBeInTheDocument();
    expect(screen.getByLabelText('Tiempo de espera')).toHaveTextContent('0:00');
    act(() => void vi.advanceTimersByTime(25_000));
    expect(screen.getByLabelText('Tiempo de espera')).toHaveTextContent('0:25');
    vi.useRealTimers();
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
