import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import type { PlayerError } from './playerMachine';
import { PlayerErrorScreen, type PlayerErrorScreenProps } from './PlayerErrorScreen';

const error = (overrides: Partial<PlayerError> = {}): PlayerError => ({
  kind: 'failed',
  code: 'internal_error',
  message: 'Se ha producido un error inesperado (internal_error).',
  detail: null,
  ...overrides,
});

function renderScreen(props: Partial<PlayerErrorScreenProps> = {}) {
  const handlers = { onRetry: vi.fn(), onTry: vi.fn(), onBack: vi.fn() };
  render(<PlayerErrorScreen error={error()} itemType="film" alternatives={[]} {...handlers} {...props} />);
  return handlers;
}

describe('PlayerErrorScreen', () => {
  it('busy: the message with [Reintentar] [Volver]', async () => {
    const message =
      'El servidor ya está convirtiendo el número máximo de vídeos. Detén la reproducción en otro dispositivo y vuelve a intentarlo.';
    const { onRetry, onBack } = renderScreen({ error: error({ kind: 'busy', code: 'too_many_sessions', message }) });
    expect(screen.getByRole('alert')).toHaveTextContent(message);
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Volver' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('gone (item 404): the item-type copy and only [Volver]', () => {
    renderScreen({ error: error({ kind: 'gone', code: 'not_found', message: 'x' }) });
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Esta película no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.',
    );
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Volver' })).toBeInTheDocument();
  });

  it('gone (file_missing on an episode): the episode copy plus the file_missing message', () => {
    const message = 'Falta el archivo de vídeo en el disco (¿está conectado el disco?).';
    renderScreen({ itemType: 'episode', error: error({ kind: 'gone', code: 'file_missing', message }) });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Este episodio no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.');
    expect(alert).toHaveTextContent(message);
  });

  it('failed transcoder_failed: the catalog message, the detail in a collapsed block and [Reintentar]', () => {
    renderScreen({
      error: error({
        code: 'transcoder_failed',
        message: 'El servidor no ha podido preparar este archivo para reproducirlo.',
        detail: 'ffmpeg exited with 1',
      }),
    });
    expect(screen.getByRole('alert')).toHaveTextContent('El servidor no ha podido preparar este archivo para reproducirlo.');
    const details = screen.getByText('Detalles técnicos').closest('details') as HTMLElement;
    expect(details).not.toHaveAttribute('open');
    expect(within(details).getByText('ffmpeg exited with 1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });

  it('version_failed: no Retry, no detail; one button per alternative plus [Volver]', async () => {
    const alt = f.mediaFile({ id: 92 });
    const { onTry } = renderScreen({
      error: error({ kind: 'version_failed', code: 'transcoder_failed', message: 'El servidor no puede preparar esta versión para reproducirla.' }),
      alternatives: [alt],
    });
    expect(screen.getByRole('alert')).toHaveTextContent('El servidor no puede preparar esta versión para reproducirla.');
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    expect(screen.queryByText('Detalles técnicos')).not.toBeInTheDocument();
    const group = screen.getByRole('group', { name: 'Otras versiones' });
    await userEvent.click(within(group).getByRole('button', { name: 'Probar 1080p H264 · AAC 2.0 · 23,5 GB' }));
    expect(onTry).toHaveBeenCalledWith(alt);
    expect(screen.getByRole('button', { name: 'Volver' })).toBeInTheDocument();
  });

  it('version_failed without alternatives offers only [Volver]', () => {
    renderScreen({ error: error({ kind: 'version_failed', message: 'El servidor no puede preparar esta versión para reproducirla.' }) });
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Volver']);
  });

  it.each([
    ['prepare_timeout', 'Este vídeo está tardando demasiado en prepararse. Vuelve a intentarlo en unos minutos.'],
    ['decode', 'Este navegador no ha podido descodificar el archivo.'],
  ] as const)('%s: message with [Reintentar] [Volver]', (kind, message) => {
    renderScreen({ error: error({ kind, message }) });
    expect(screen.getByRole('alert')).toHaveTextContent(message);
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Reintentar', 'Volver']);
  });
});
