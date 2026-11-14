// The trailing "Ver todo" card of a Home row.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { findProbedLocation, renderWithProviders } from '../../tests/renderWithProviders';
import { SeeAllCard } from './SeeAllCard';

describe('SeeAllCard', () => {
  it('is a link named "Ver todo: <row>" with the visible text "Ver todo"', () => {
    renderWithProviders(<SeeAllCard to="/films?sort=added" rowTitle="Películas" />);
    const link = screen.getByRole('link', { name: 'Ver todo: Películas' });
    expect(link).toHaveAttribute('href', '/films?sort=added');
    expect(link).toHaveTextContent(/^Ver todo$/);
  });

  it('navigates to the target', async () => {
    const { user } = renderWithProviders(<SeeAllCard to="/films?sort=added" rowTitle="Películas" />, {
      path: '/start',
      route: '/start',
    });
    await user.click(screen.getByRole('link', { name: 'Ver todo: Películas' }));
    expect(await findProbedLocation()).toBe('/films?sort=added');
  });

  it('the frame is 2:3 (CSS source: jsdom has no layout)', () => {
    const css = readFileSync(join(__dirname, 'SeeAllCard.module.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const frame = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => (m[1] ?? '').trim() === '.frame');
    expect(frame).toHaveLength(1);
    expect(frame[0]![2]).toMatch(/aspect-ratio:\s*2 \/ 3/);
  });
});
