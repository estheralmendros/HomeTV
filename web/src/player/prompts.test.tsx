import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { NextEpisodePrompt } from './NextEpisodePrompt';
import { NextPartPrompt } from './NextPartPrompt';
import { TapToPlayOverlay } from './TapToPlayOverlay';

describe('NextEpisodePrompt', () => {
  const next = f.episodeDetail({ id: 346, episode_number: 4, title: 'The Fire' });

  it('reads "A continuación: T2 · E4" with the title, still and actions; no countdown before the end', async () => {
    const onPlayNow = vi.fn();
    const onCancel = vi.fn();
    render(<NextEpisodePrompt episode={next} secondsLeft={null} onPlayNow={onPlayNow} onCancel={onCancel} />);
    const prompt = screen.getByRole('dialog', { name: 'Siguiente episodio' });
    expect(prompt).toHaveTextContent('A continuación: T2 · E4');
    expect(prompt).toHaveTextContent('The Fire');
    expect(screen.queryByText(/Se reproducirá en/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reproducir ahora' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(onPlayNow).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('shows "Se reproducirá en N s" during the countdown', () => {
    render(<NextEpisodePrompt episode={next} secondsLeft={7} onPlayNow={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByText('Se reproducirá en 7 s')).toBeInTheDocument();
  });

  it('falls back to "Episodio N" without a title', () => {
    render(<NextEpisodePrompt episode={{ ...next, title: null }} secondsLeft={null} onPlayNow={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole('dialog')).toHaveTextContent('Episodio 4');
  });
});

describe('NextPartPrompt', () => {
  it('"Parte 1 terminada" with [Reproducir parte 2] [Volver], no countdown', async () => {
    const onPlay = vi.fn();
    const onBack = vi.fn();
    render(<NextPartPrompt finishedPart={1} nextPart={2} onPlay={onPlay} onBack={onBack} />);
    expect(screen.getByRole('dialog', { name: 'Siguiente parte' })).toHaveTextContent('Parte 1 terminada');
    expect(screen.queryByText(/Se reproducirá/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reproducir parte 2' }));
    await userEvent.click(screen.getByRole('button', { name: 'Volver' }));
    expect(onPlay).toHaveBeenCalledTimes(1);
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});

describe('TapToPlayOverlay', () => {
  it('a large focused "Toca para reproducir" button', async () => {
    const onPlay = vi.fn();
    render(<TapToPlayOverlay onPlay={onPlay} />);
    const button = screen.getByRole('button', { name: 'Toca para reproducir' });
    expect(button).toHaveFocus();
    await userEvent.click(button);
    expect(onPlay).toHaveBeenCalledTimes(1);
  });
});
