import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { UnsupportedMessage, type UnsupportedMessageProps } from './UnsupportedMessage';

function renderMessage(props: Partial<UnsupportedMessageProps> = {}) {
  const onTry = vi.fn();
  const onBack = vi.fn();
  render(
    <UnsupportedMessage
      reason="video_codec_unsupported"
      detail="Video codec hevc is not supported by this client"
      codec="hevc"
      alternatives={[]}
      onTry={onTry}
      onBack={onBack}
      {...props}
    />,
  );
  return { onTry, onBack };
}

describe('UnsupportedMessage', () => {
  it.each([
    [
      'video_codec_unsupported',
      'No se puede reproducir este vídeo en este navegador',
      'Este navegador no admite el códec de vídeo HEVC. Prueba otra versión, otro navegador (Safari o Edge pueden admitir HEVC) o la app del televisor.',
    ],
    [
      'hls_not_supported',
      'Este navegador no puede reproducir vídeo convertido',
      'Usa una versión reciente de Chrome, Firefox, Edge o Safari.',
    ],
    [
      'probe_failed',
      'No se ha podido analizar este archivo',
      'Puede que el archivo esté dañado. Un administrador puede revisarlo en Administración → Sin identificar.',
    ],
    [
      'duration_unknown',
      'Este archivo no se puede convertir para reproducirlo',
      'Se desconoce su duración, así que solo se puede reproducir directamente, y este navegador no puede reproducirlo directamente. Prueba otra versión o la app del televisor.',
    ],
    [
      'keyframes_too_sparse',
      'Este archivo no se puede convertir para reproducirlo',
      'Sus fotogramas clave están demasiado separados para dividirlo en segmentos. Prueba otra versión, un navegador que pueda reproducir el archivo original directamente o la app del televisor.',
    ],
    ['something_new', 'No se puede reproducir este archivo en este navegador', 'Prueba otra versión o la app del televisor.'],
  ])('%s: heading and body from the catalog', (reason, heading, body) => {
    renderMessage({ reason });
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
    expect(screen.getByText(body)).toBeInTheDocument();
  });

  it('no_video_stream has a heading and no body', () => {
    renderMessage({ reason: 'no_video_stream' });
    expect(screen.getByRole('heading', { name: 'Este archivo no tiene vídeo' })).toBeInTheDocument();
    expect(screen.queryByText(/Prueba otra versión/)).not.toBeInTheDocument();
  });

  it('the server detail only appears inside the collapsed "Detalles técnicos" block', () => {
    renderMessage();
    const summary = screen.getByText('Detalles técnicos');
    const details = summary.closest('details');
    expect(details).not.toHaveAttribute('open');
    expect(within(details as HTMLElement).getByText('Video codec hevc is not supported by this client')).toBeInTheDocument();
  });

  it('lists the alternatives under "Otras versiones" and re-requests the chosen one', async () => {
    const alt = f.mediaFile({ id: 92, file_name: 'Dune 1080p.mkv' });
    const { onTry, onBack } = renderMessage({ alternatives: [alt] });
    const group = screen.getByRole('group', { name: 'Otras versiones' });
    await userEvent.click(within(group).getByRole('button', { name: 'Probar Dune 1080p' }));
    expect(onTry).toHaveBeenCalledWith(alt);
    await userEvent.click(screen.getByRole('button', { name: 'Volver' }));
    expect(onBack).toHaveBeenCalled();
  });

  it('hides "Otras versiones" without alternatives', () => {
    renderMessage();
    expect(screen.queryByRole('group', { name: 'Otras versiones' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Volver' })).toBeInTheDocument();
  });
});
