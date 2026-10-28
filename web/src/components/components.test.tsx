import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import { findProbedLocation, renderWithProviders } from '../../tests/renderWithProviders';
import { setTokenGetter } from '@/api/client';
import { Artwork } from './Artwork';
import { Avatar, ProfileAvatar, avatarColor, initialOf } from './Avatar';
import { CastRow } from './CastRow';
import { EmptyState } from './EmptyState';
import { EpisodeCard } from './EpisodeCard';
import { ErrorState } from './ErrorState';
import { Modal } from './Modal';
import { PosterCard } from './PosterCard';
import { ProgressBar, progressPercent } from './ProgressBar';
import { Row } from './Row';
import { SkeletonGrid, SkeletonRow } from './Skeleton';
import { ToastProvider, useToast } from './Toast';
import { WatchedToggle } from './WatchedToggle';

describe('CastRow', () => {
  it('renders a person listed twice (two characters) without a duplicate-key warning', () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const cast = [
      f.castMember({ person_id: 7, name: 'Tom Hardy', character: 'Ronnie Kray', profile_image: null }),
      f.castMember({ person_id: 7, name: 'Tom Hardy', character: 'Reggie Kray', profile_image: null }),
    ];
    renderWithProviders(<CastRow cast={cast} />);
    expect(screen.getByText('Ronnie Kray')).toBeInTheDocument();
    expect(screen.getByText('Reggie Kray')).toBeInTheDocument();
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('each member is a link to /people/{person_id} named "name, character"', () => {
    renderWithProviders(
      <CastRow cast={[f.castMember(), f.castMember({ person_id: 8, name: 'Zendaya', character: null, profile_image: null })]} />,
    );
    expect(screen.getByRole('link', { name: 'Timothée Chalamet, Paul Atreides' })).toHaveAttribute('href', '/people/3');
    expect(screen.getByRole('link', { name: 'Zendaya' })).toHaveAttribute('href', '/people/8');
  });

  it('Enter on the focused member navigates to the actor page', async () => {
    const { user } = renderWithProviders(<CastRow cast={[f.castMember()]} />, { path: '/films/12', route: '/films/12' });
    const link = screen.getByRole('link', { name: 'Timothée Chalamet, Paul Atreides' });
    link.focus();
    expect(link).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await findProbedLocation()).toBe('/people/3');
  });

  it('requests only the small image (no srcset)', () => {
    renderWithProviders(<CastRow cast={[f.castMember()]} />, { session: f.session({ token: 'tok' }) });
    const img = screen.getByRole('link', { name: /Timothée Chalamet/ }).querySelector('img')!;
    expect(img).toHaveAttribute('src', '/api/artwork/w185/chalamet.jpg?token=tok');
    expect(img).not.toHaveAttribute('srcset');
  });

  it('a member without a photo, or whose photo fails, shows the initial in the photo circle', () => {
    renderWithProviders(
      <CastRow cast={[f.castMember({ profile_image: null }), f.castMember({ person_id: 8, name: 'zendaya', character: null })]} />,
    );
    const noPhoto = screen.getByRole('link', { name: /Timothée Chalamet/ });
    expect(noPhoto.querySelector('img')).toBeNull();
    expect(within(noPhoto).getByText('T')).toHaveAttribute('aria-hidden', 'true');
    const broken = screen.getByRole('link', { name: 'zendaya' });
    fireEvent.error(broken.querySelector('img')!);
    expect(within(broken).getByText('Z')).toBeInTheDocument();
    expect(broken.querySelector('img')).toBeNull();
  });
});

describe('PosterCard', () => {
  it('is a link named "Dune (2021)" with a lazy tokenized poster and 2x srcset', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={f.image('dune')} />, {
      session: f.session({ token: 'tok' }),
    });
    const link = screen.getByRole('link', { name: 'Dune (2021)' });
    expect(link).toHaveAttribute('href', '/films/12');
    const img = link.querySelector('img')!;
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('src', '/api/artwork/w342/dune.jpg?token=tok');
    expect(img).toHaveAttribute('srcset', '/api/artwork/w780/dune.jpg?token=tok 2x');
    expect(img).toHaveAttribute('alt', '');
    expect(link).toHaveTextContent('Dune');
    expect(link).toHaveTextContent('2021');
  });

  it('renders a placeholder tile with the title when the image is null', () => {
    renderWithProviders(<PosterCard to="/shows/7" title="The Office" year={null} poster={null} />);
    expect(screen.getByRole('link', { name: 'The Office' })).toBeInTheDocument();
    expect(screen.getByTestId('artwork-placeholder')).toHaveTextContent('The Office');
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('shows the progress bar and a tag', () => {
    renderWithProviders(
      <PosterCard to="/films/12" title="Dune" poster={null} progress={{ position: 4140, duration: 9000 }} tag="Sin identificar" />,
    );
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '46');
    expect(screen.getByText('Sin identificar')).toBeInTheDocument();
  });

  it('watched hides the progress bar and the badge', () => {
    renderWithProviders(
      <PosterCard to="/films/12" title="Dune" poster={null} watched badge="3 sin ver" progress={{ position: 1, duration: 2 }} />,
    );
    expect(screen.getByText('✓')).toBeInTheDocument();
    expect(screen.queryByText('3 sin ver')).not.toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('with a subtitle: shown below the year and part of the link name', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={null} subtitle="como Paul Atreides" />);
    const link = screen.getByRole('link', { name: 'Dune (2021), como Paul Atreides' });
    expect(within(link).getByText('como Paul Atreides')).toBeInTheDocument();
    expect(link).toHaveTextContent('Dune2021como Paul Atreides');
  });

  it('a null subtitle changes nothing', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={null} subtitle={null} />);
    expect(screen.getByRole('link', { name: 'Dune (2021)' })).toHaveTextContent(/2021$/);
  });

  it('shows a badge such as "12 sin ver"', () => {
    renderWithProviders(<PosterCard to="/shows/7" title="The Office" poster={null} badge="12 sin ver" />);
    expect(screen.getByText('12 sin ver')).toBeInTheDocument();
  });
});

describe('Artwork', () => {
  it('falls back to the title placeholder when the image fails to load (e.g. 404, expired token)', () => {
    const { container, rerender } = render(<Artwork image={f.image('dune')} title="Dune" />);
    fireEvent.error(container.querySelector('img')!);
    expect(screen.getByTestId('artwork-placeholder')).toHaveTextContent('Dune');
    expect(container.querySelector('img')).toBeNull();
    // Another image gets its own attempt.
    rerender(<Artwork image={f.image('dune-2')} title="Dune" />);
    expect(container.querySelector('img')).toHaveAttribute('src', expect.stringContaining('dune-2'));
    expect(screen.queryByTestId('artwork-placeholder')).not.toBeInTheDocument();
  });

  it('uses large as the 2x srcset by default and small only with largeAt2x={false}', () => {
    const { container, rerender } = render(<Artwork image={f.image('dune')} title="Dune" />);
    expect(container.querySelector('img')).toHaveAttribute('srcset', '/api/artwork/w780/dune.jpg 2x');
    rerender(<Artwork image={f.image('dune')} title="Dune" largeAt2x={false} />);
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/artwork/w342/dune.jpg');
    expect(container.querySelector('img')).not.toHaveAttribute('srcset');
  });

  it('a custom placeholder replaces the title tile for a null or failed image', () => {
    const { container, rerender } = render(<Artwork image={null} title="Dune" placeholder={<span>D</span>} />);
    expect(screen.getByText('D')).toBeInTheDocument();
    expect(screen.queryByTestId('artwork-placeholder')).not.toBeInTheDocument();
    rerender(<Artwork image={f.image('dune')} title="Dune" placeholder={<span>D</span>} />);
    expect(screen.queryByText('D')).not.toBeInTheDocument();
    fireEvent.error(container.querySelector('img')!);
    expect(screen.getByText('D')).toBeInTheDocument();
  });

  it('loads lazily by default and eagerly with loading="eager"', () => {
    const { container, rerender } = render(<Artwork image={f.image('dune')} title="Dune" />);
    expect(container.querySelector('img')).toHaveAttribute('loading', 'lazy');
    rerender(<Artwork image={f.image('dune')} title="Dune" loading="eager" />);
    expect(container.querySelector('img')).toHaveAttribute('loading', 'eager');
  });

  it('marks its frame with data-kind (poster by default)', () => {
    const { container, rerender } = render(<Artwork image={null} title="Dune" />);
    expect(container.firstElementChild).toHaveAttribute('data-kind', 'poster');
    rerender(<Artwork image={null} title="Dune" kind="profile" />);
    expect(container.firstElementChild).toHaveAttribute('data-kind', 'profile');
  });
});

describe('EpisodeCard', () => {
  it('links with its accessible name and shows the show title, code line and progress', () => {
    renderWithProviders(
      <EpisodeCard
        to="/watch/episode/345"
        still={f.image('s', 'still')}
        showTitle="The Office"
        line="T2 · E3 · Office Olympics"
        ariaLabel="The Office · T2 · E3 · Office Olympics"
        progress={{ position: 660, duration: 1320 }}
      />,
    );
    const link = screen.getByRole('link', { name: 'The Office · T2 · E3 · Office Olympics' });
    expect(link).toHaveAttribute('href', '/watch/episode/345');
    expect(link).toHaveTextContent('T2 · E3 · Office Olympics');
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
  });
});

describe('Row', () => {
  it('renders a titled section whose ‹ › buttons scroll by one viewport width', async () => {
    render(
      <Row title="Seguir viendo">
        <span>a</span>
      </Row>,
    );
    expect(screen.getByRole('region', { name: 'Seguir viendo' })).toBeInTheDocument();
    const track = screen.getByTestId('row-track');
    Object.defineProperty(track, 'clientWidth', { value: 800 });
    const scrollBy = vi.fn();
    Object.assign(track, { scrollBy });
    await userEvent.click(screen.getByRole('button', { name: 'Siguiente' }));
    await userEvent.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(scrollBy.mock.calls).toEqual([[{ left: 800, behavior: 'smooth' }], [{ left: -800, behavior: 'smooth' }]]);
  });
});

describe('ProgressBar', () => {
  it('computes a clamped percentage', () => {
    expect(progressPercent(4140, 9000)).toBe(46);
    expect(progressPercent(10, 0)).toBe(0);
    expect(progressPercent(20, 10)).toBe(100);
    expect(progressPercent(-5, 10)).toBe(0);
  });

  it('exposes progressbar semantics with a Spanish label', () => {
    render(<ProgressBar value={4140} max={9000} />);
    const bar = screen.getByRole('progressbar', { name: '46 % visto' });
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });
});

describe('WatchedToggle', () => {
  it('reads "Marcar como visto" / "Marcar como no visto" and reports the next value', async () => {
    const onToggle = vi.fn();
    const { rerender } = render(<WatchedToggle watched={false} onToggle={onToggle} />);
    await userEvent.click(screen.getByRole('button', { name: /Marcar como visto/ }));
    expect(onToggle).toHaveBeenLastCalledWith(true);
    rerender(<WatchedToggle watched onToggle={onToggle} />);
    const button = screen.getByRole('button', { name: /Marcar como no visto/ });
    expect(button).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(button);
    expect(onToggle).toHaveBeenLastCalledWith(false);
  });

  it('compact mode keeps the accessible name', () => {
    render(<WatchedToggle watched={false} onToggle={vi.fn()} compact />);
    expect(screen.getByRole('button', { name: 'Marcar como visto' })).toHaveTextContent('○');
  });
});

describe('EmptyState / ErrorState', () => {
  it('EmptyState shows the message and actions', () => {
    render(
      <EmptyState message="Todo está identificado.">
        <button type="button">x</button>
      </EmptyState>,
    );
    expect(screen.getByText('Todo está identificado.')).toBeInTheDocument();
  });

  it('ErrorState is an alert with [Reintentar]', async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="No se puede conectar con el servidor." onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('No se puede conectar con el servidor.');
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('Avatar', () => {
  it('shows the uppercase initial over the avatar color', () => {
    render(<Avatar name="ñandú" color="#E50914" />);
    expect(screen.getByText('Ñ')).toHaveStyle({ background: '#E50914' });
    expect(initialOf('  ')).toBe('?');
    expect(avatarColor(f.avatars(), 'avatar-04')).toBe('#7ED321');
    expect(avatarColor(undefined, 'avatar-04')).toBe('#4A90E2');
  });

  it('ProfileAvatar takes its color from info.avatars', () => {
    setTokenGetter(() => null);
    renderWithProviders(<ProfileAvatar name="Kids" avatarId="avatar-04" />);
    expect(screen.getByText('K')).toHaveStyle({ background: '#7ED321' });
  });
});

describe('Skeletons', () => {
  it('Home rows have 8 placeholders and the grid 18', () => {
    render(
      <>
        <SkeletonRow />
        <SkeletonGrid />
      </>,
    );
    const [row, grid] = screen.getAllByRole('progressbar', { name: 'Cargando…' });
    expect(row!.querySelectorAll('[data-testid="skeleton-card"]')).toHaveLength(8);
    expect(grid!.querySelectorAll('[data-testid="skeleton-card"]')).toHaveLength(18);
  });
});

describe('Toast', () => {
  function Trigger({ durationMs }: { durationMs?: number }) {
    const toast = useToast();
    return (
      <button type="button" onClick={() => toast.show('Ya hay un escaneo en curso.', { durationMs, action: { label: 'Abrir', onClick: onAction } })}>
        show
      </button>
    );
  }
  const onAction = vi.fn();

  it('announces in a polite live region and auto-dismisses after 4 s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    );
    await user.click(screen.getByRole('button', { name: 'show' }));
    const region = screen.getByRole('status', { name: 'Notificaciones' });
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveTextContent('Ya hay un escaneo en curso.');
    act(() => vi.advanceTimersByTime(4000));
    expect(region).not.toHaveTextContent('Ya hay un escaneo en curso.');
  });

  it('runs the action and closes on ✕', async () => {
    render(
      <ToastProvider>
        <Trigger durationMs={0} />
      </ToastProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'show' }));
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Ya hay un escaneo en curso.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'show' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar notificación' }));
    expect(screen.queryByText('Ya hay un escaneo en curso.')).not.toBeInTheDocument();
  });
});

describe('Modal', () => {
  function Host() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button type="button" onClick={() => setOpen(true)}>
          open
        </button>
        {open && (
          <Modal title="Editar perfil" onClose={() => setOpen(false)}>
            <input aria-label="first" />
            <button type="button">last</button>
          </Modal>
        )}
      </>
    );
  }

  it('is a labelled modal dialog that traps focus, closes on Esc and restores focus', async () => {
    const user = userEvent.setup();
    render(<Host />);
    const opener = screen.getByRole('button', { name: 'open' });
    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: 'Editar perfil' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('textbox', { name: 'first' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'last' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('textbox', { name: 'first' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'last' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
});
