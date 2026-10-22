import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { renderWithProviders } from '../../../tests/renderWithProviders';
import { AboutPage } from './AboutPage';

describe('AboutPage', () => {
  it('shows the app name, server version, TMDB logo, attribution, link and privacy note', () => {
    renderWithProviders(<AboutPage />, { info: f.info({ version: '0.3.1' }) });
    expect(screen.getByRole('heading', { level: 1, name: 'Acerca de' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'HomeTV' })).toBeInTheDocument();
    expect(screen.getByText('Versión del servidor: 0.3.1')).toBeInTheDocument();
    const logo = screen.getByRole('img', { name: 'Logotipo de TMDB' });
    expect(Number(logo.getAttribute('width'))).toBeGreaterThanOrEqual(120);
    expect(
      screen.getByText('Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'The Movie Database (TMDB)' })).toHaveAttribute('href', 'https://www.themoviedb.org');
    expect(screen.getByText('Los vídeos se reproducen desde tu propio servidor. Nada sale de tu tailnet.')).toBeInTheDocument();
    expect(document.title).toBe('Acerca de · HomeTV');
  });
});
