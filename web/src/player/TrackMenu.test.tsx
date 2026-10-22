import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { AudioMenu, SubtitleMenu, type AudioMenuProps, type SubtitleMenuProps } from './TrackMenu';

function renderSubtitles(props: Partial<SubtitleMenuProps> = {}) {
  const handlers = { onToggle: vi.fn(), onClose: vi.fn(), onSelect: vi.fn() };
  render(
    <SubtitleMenu
      tracks={[
        f.subtitleTrack({ id: 'emb-3', language: 'spa' }),
        f.subtitleTrack({ id: 'ext-0', language: 'eng', forced: true, source: 'external' }),
        f.subtitleTrack({ id: 'emb-5', kind: 'image', language: 'fre' }),
      ]}
      selected="off"
      pending={new Set()}
      open
      {...handlers}
      {...props}
    />,
  );
  return handlers;
}

describe('SubtitleMenu', () => {
  it('lists "Desactivados" and the text tracks only, with ✓ on the selection', () => {
    renderSubtitles({ selected: 'emb-3' });
    const menu = screen.getByRole('menu', { name: 'Subtítulos' });
    const items = within(menu).getAllByRole('menuitemradio');
    expect(items.map((i) => i.textContent)).toEqual(['Desactivados', '✓ Español', 'Inglés · Forzados · Externo']);
    expect(items[1]).toHaveAttribute('aria-checked', 'true');
    expect(items[0]).toHaveAttribute('aria-checked', 'false');
  });

  it('selecting an entry calls onSelect with its id (or "off")', async () => {
    const { onSelect } = renderSubtitles({ selected: 'emb-3' });
    await userEvent.click(screen.getByRole('menuitemradio', { name: /Inglés/ }));
    expect(onSelect).toHaveBeenLastCalledWith('ext-0');
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Desactivados' }));
    expect(onSelect).toHaveBeenLastCalledWith('off');
  });

  it('a pending track shows a spinner and "Preparando…"', () => {
    renderSubtitles({ selected: 'emb-3', pending: new Set(['emb-3']) });
    const item = screen.getByRole('menuitemradio', { name: /Español/ });
    expect(item).toHaveTextContent('Español · Preparando…');
    expect(within(item).getByRole('progressbar')).toBeInTheDocument();
  });

  it('no text tracks: "No hay subtítulos disponibles" plus the image note when image tracks exist', () => {
    renderSubtitles({ tracks: [f.subtitleTrack({ id: 'emb-5', kind: 'image' })] });
    expect(screen.getByText('No hay subtítulos disponibles')).toBeInTheDocument();
    expect(screen.getByText('Los subtítulos de imagen no se admiten en el navegador')).toBeInTheDocument();
  });

  it('no tracks at all: no image note', () => {
    renderSubtitles({ tracks: [] });
    expect(screen.getByText('No hay subtítulos disponibles')).toBeInTheDocument();
    expect(screen.queryByText('Los subtítulos de imagen no se admiten en el navegador')).not.toBeInTheDocument();
  });

  it('the button toggles the menu and is labelled "Subtítulos"', async () => {
    const { onToggle } = renderSubtitles({ open: false });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Subtítulos' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(button);
    expect(onToggle).toHaveBeenCalled();
  });
});

function renderAudio(props: Partial<AudioMenuProps> = {}) {
  const handlers = { onToggle: vi.fn(), onClose: vi.fn(), onSelect: vi.fn() };
  const view = render(
    <AudioMenu
      tracks={[
        f.audioTrack({ index: 1, codec: 'eac3', channels: 6, language: 'eng', title: 'Surround' }),
        f.audioTrack({ index: 2, codec: 'ac3', channels: 2, language: 'spa' }),
      ]}
      current={1}
      disabled={false}
      open
      {...handlers}
      {...props}
    />,
  );
  return { ...handlers, view };
}

describe('AudioMenu', () => {
  it('lists language · codec channels · title with ✓ on the current index', () => {
    renderAudio();
    const items = within(screen.getByRole('menu', { name: 'Audio' })).getAllByRole('menuitemradio');
    expect(items.map((i) => i.textContent)).toEqual(['✓ Inglés · EAC3 5.1 · Surround', 'Español · AC3 2.0']);
  });

  it('selecting another track calls onSelect with its index', async () => {
    const { onSelect } = renderAudio();
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Español · AC3 2.0' }));
    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('is hidden with fewer than 2 tracks', () => {
    renderAudio({ tracks: [f.audioTrack()] });
    expect(screen.queryByRole('button', { name: 'Audio' })).not.toBeInTheDocument();
  });

  it('is disabled where the machine has no SWITCH_AUDIO row', () => {
    renderAudio({ disabled: true, open: false });
    expect(screen.getByRole('button', { name: 'Audio' })).toBeDisabled();
  });
});
