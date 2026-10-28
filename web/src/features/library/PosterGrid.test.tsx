import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import * as f from '../../../tests/fixtures';
import { renderWithProviders } from '../../../tests/renderWithProviders';
import type { FilmSummary, ShowSummary } from '@/api/types';
import { PosterGrid } from './PosterGrid';

describe('PosterGrid', () => {
  it('without subtitleFor: cards keep their plain names', () => {
    renderWithProviders(<PosterGrid items={[f.filmSummary()]} />);
    expect(screen.getByRole('link', { name: 'Dune (2021)' })).toBeInTheDocument();
  });

  it('subtitleFor is called per item and its result shown on films and shows', () => {
    const subtitleFor = vi.fn((item: FilmSummary | ShowSummary) => (item.type === 'film' ? 'como Paul Atreides' : null));
    const film = f.filmSummary();
    const show = f.showSummary();
    renderWithProviders(<PosterGrid items={[film, show]} subtitleFor={subtitleFor} />);
    expect(subtitleFor).toHaveBeenCalledWith(film);
    expect(subtitleFor).toHaveBeenCalledWith(show);
    const items = screen.getAllByRole('listitem');
    expect(within(items[0]!).getByRole('link', { name: 'Dune (2021), como Paul Atreides' })).toHaveAttribute('href', '/films/12');
    expect(within(items[0]!).getByText('como Paul Atreides')).toBeInTheDocument();
    expect(within(items[1]!).getByRole('link', { name: 'The Office (2005)' })).toHaveAttribute('href', '/shows/7');
  });

  it('a show subtitle goes through SummaryCard too (badge kept)', () => {
    renderWithProviders(<PosterGrid items={[f.showSummary()]} subtitleFor={() => 'como Pam Beesly'} />);
    const link = screen.getByRole('link', { name: 'The Office (2005), como Pam Beesly' });
    expect(within(link).getByText('150 sin ver')).toBeInTheDocument();
    expect(within(link).getByText('como Pam Beesly')).toBeInTheDocument();
  });
});
