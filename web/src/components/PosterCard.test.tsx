// The viewer's rating badge on poster cards.
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { renderWithProviders } from '../../tests/renderWithProviders';
import { PosterCard } from './PosterCard';
import { SummaryCard } from './SummaryCard';

describe('PosterCard rating badge', () => {
  it('my_rating: one filled star + "4,5" in the poster, in the link name', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={null} myRating={4.5} />);
    const link = screen.getByRole('link', { name: 'Dune (2021), tu puntuación: 4,5 estrellas' });
    const badge = within(link).getByTestId('rating-badge');
    expect(badge).toHaveTextContent('4,5');
    expect(badge.querySelectorAll('svg[data-fill="full"]')).toHaveLength(1);
    expect(badge).toHaveAttribute('aria-hidden', 'true');
  });

  it('composed with the subtitle', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={null} subtitle="como Paul Atreides" myRating={1} />);
    expect(screen.getByRole('link', { name: 'Dune (2021), como Paul Atreides, tu puntuación: 1 estrella' })).toBeInTheDocument();
  });

  it.each([
    ['null', { myRating: null }],
    ['absent', {}],
  ])('nothing when %s', (_name, props) => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" year={2021} poster={null} {...props} />);
    expect(screen.getByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
    expect(screen.queryByTestId('rating-badge')).not.toBeInTheDocument();
  });

  it('keeps the watched corner and the progress bar', () => {
    renderWithProviders(<PosterCard to="/films/12" title="Dune" poster={null} myRating={3} progress={{ position: 10, duration: 100 }} />);
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
    expect(screen.getByTestId('rating-badge')).toHaveTextContent('3');
  });

  it('SummaryCard passes my_rating for films and shows', () => {
    renderWithProviders(
      <>
        <SummaryCard item={f.filmSummary({ my_rating: 4 })} />
        <SummaryCard item={f.showSummary({ my_rating: 2.5 })} />
        <SummaryCard item={f.filmSummary({ id: 13, title: 'Heat', year: 1995 })} />
      </>,
    );
    expect(screen.getByRole('link', { name: 'Dune (2021), tu puntuación: 4 estrellas' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'The Office (2005), tu puntuación: 2,5 estrellas' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Heat (1995)' })).toBeInTheDocument();
  });
});
