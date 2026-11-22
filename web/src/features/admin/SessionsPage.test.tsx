import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { AdminSessions } from '@/api/types';
import * as f from '../../../tests/fixtures';
import { advance, until } from '../../../tests/flush';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { useFakePlayerTimers } from '../../../tests/player';
import { currentUrl, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';

const copyFilm = f.adminSession({
  session_id: 'a',
  profile_name: 'Lucía',
  title: 'Dune',
  file_name: 'Dune.2021.2160p.mkv',
  audio_action: 'aac_5_1',
  state: 'running',
  last_segment: 3,
  position_seconds: 18,
  started_at: new Date(2026, 9, 31, 21, 10).toISOString(),
});
const h264Episode = f.adminSession({
  session_id: 'b',
  profile_name: 'Esther',
  item_type: 'episode',
  title: 'Jane the Virgin',
  season_number: 4,
  episode_number: 1,
  file_name: 'Jane.The.Virgin.S04E01.mkv',
  video_action: 'h264',
  audio_action: 'copy',
  state: 'running',
  last_segment: 12,
  position_seconds: 72,
  speed: 1.4,
  started_at: new Date(2026, 9, 31, 21, 4).toISOString(),
});

function serve(response: AdminSessions) {
  server.use(http.get(`${API}/admin/sessions`, () => HttpResponse.json(response)));
}

function renderSessions(options: ProviderOptions = {}) {
  return renderApp({ route: '/admin/sessions', profile: f.adminProfile(), adminUnlock: f.unlockResponse(), ...options });
}

const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

describe('SessionsPage', () => {
  it('renders a copy film row and an h264 episode row with the Spanish labels', async () => {
    serve(f.adminSessions({ sessions: [h264Episode, copyFilm], active_sessions: 2, active_transcodes: 1 }));
    renderSessions();
    const table = await screen.findByRole('table', { name: 'Reproducciones' });
    expect(within(table).getAllByRole('columnheader').map((c) => c.textContent)).toEqual([
      'Perfil', 'Título', 'Archivo', 'Vídeo', 'Audio', 'Estado', 'Inicio', 'Último segmento', 'Velocidad',
    ]);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'Esther', 'Jane the Virgin · T4 · E1', 'Jane.The.Virgin.S04E01.mkv', 'Conversión H.264', 'Copia', 'Convirtiendo',
      '21:04', '12 · 1:12', '1,4×', 'Detener',
    ]);
    expect(within(rows[1]!).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'Lucía', 'Dune', 'Dune.2021.2160p.mkv', 'Copia', 'AAC 5.1', 'Emitiendo', '21:10', '3 · 0:18', '—', 'Detener',
    ]);
    expect(within(rows[1]!).getByText('Dune.2021.2160p.mkv')).toHaveAttribute('title', 'Dune.2021.2160p.mkv');
    expect(within(rows[1]!).getByRole('button', { name: 'Detener la reproducción de Dune' })).toBeInTheDocument();
  });

  it('shows the other states, an unidentified title and a deleted profile', async () => {
    serve(f.adminSessions({
      sessions: [
        f.adminSession({ session_id: 'x', item_type: null, title: null, profile_name: null, state: 'failed', file_name: 'x.mkv' }),
        f.adminSession({ session_id: 'y', state: 'throttled', video_action: 'h264', audio_action: 'none' }),
        f.adminSession({ session_id: 'z', state: 'done', audio_action: 'aac_stereo' }),
      ],
    }));
    renderSessions();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Sin identificar')).toBeInTheDocument();
    expect(within(table).getByText('Perfil eliminado')).toBeInTheDocument();
    for (const text of ['Error', 'En espera', 'Preparado', 'Sin audio', 'AAC estéreo']) {
      expect(within(table).getByText(text)).toBeInTheDocument();
    }
    expect(within(table).getByRole('button', { name: 'Detener la reproducción de x.mkv' })).toBeInTheDocument();
  });

  it('header and CPU line use the counts the server sends, not the rows', async () => {
    serve(f.adminSessions({
      sessions: [copyFilm, h264Episode, f.adminSession({ session_id: 'c', state: 'failed' })],
      active_sessions: 2,
      active_transcodes: 1,
      cpu: { load_1m: 2.84, load_5m: 2, cores: 4 },
    }));
    renderSessions();
    expect(await screen.findByText('Reproducciones activas: 2 de 4 · Conversiones: 1 de 2')).toBeInTheDocument();
    expect(screen.getByText('Carga de CPU: 2,8 (4 núcleos)')).toBeInTheDocument();
  });

  it('one core is "1 núcleo"', async () => {
    serve(f.adminSessions({ cpu: { load_1m: 0.4, load_5m: 0.4, cores: 1 } }));
    renderSessions();
    expect(await screen.findByText('Carga de CPU: 0,4 (1 núcleo)')).toBeInTheDocument();
  });

  it('cpu null shows "no disponible"', async () => {
    serve(f.adminSessions({ cpu: null }));
    renderSessions();
    expect(await screen.findByText('Carga de CPU: no disponible')).toBeInTheDocument();
  });

  it('empty state', async () => {
    renderSessions();
    expect(await screen.findByText('No hay ninguna reproducción en curso.')).toBeInTheDocument();
    expect(screen.getByText('Reproducciones activas: 0 de 4 · Conversiones: 0 de 2')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('a failed load shows the error and "Reintentar"', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/admin/sessions`, () =>
        ++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.adminSessions({ active_sessions: 1 })),
      ),
    );
    const { user } = renderSessions();
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText(/Reproducciones activas: 1 de 4/)).toBeInTheDocument();
  });

  it('the "Reproducciones" tab is the active one', async () => {
    const { router } = renderSessions();
    await screen.findByText('No hay ninguna reproducción en curso.');
    expect(currentUrl(router)).toBe('/admin/sessions');
    const nav = screen.getByRole('navigation', { name: 'Secciones de administración' });
    expect(within(nav).getByRole('link', { name: 'Reproducciones' })).toHaveAttribute('aria-current', 'page');
  });

  describe('phone', () => {
    it('renders one card per session and no table', async () => {
      setViewportWidth(360);
      serve(f.adminSessions({ sessions: [h264Episode, copyFilm], active_sessions: 2, active_transcodes: 1 }));
      renderSessions();
      const list = await screen.findByRole('list', { name: 'Reproducciones' });
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
      const cards = within(list).getAllByRole('listitem');
      expect(cards).toHaveLength(2);
      const first = within(cards[0]!);
      expect(first.getByText('Jane the Virgin · T4 · E1')).toBeInTheDocument();
      expect(first.getByText('Esther · Convirtiendo · 1,4×')).toBeInTheDocument();
      expect(first.getByText('Vídeo: Conversión H.264')).toBeInTheDocument();
      expect(first.getByText('Audio: Copia')).toBeInTheDocument();
      expect(first.getByText('21:04 · 12 · 1:12')).toBeInTheDocument();
      expect(first.getByRole('button', { name: 'Detener la reproducción de Jane the Virgin · T4 · E1' })).toBeInTheDocument();
      expect(within(cards[1]!).getByText('Lucía · Emitiendo · —')).toBeInTheDocument();
    });
  });

  describe('Detener', () => {
    const one = () => f.adminSessions({ sessions: [copyFilm], active_sessions: 1 });

    it('DELETEs through useAdminAction, refetches the list and toasts', async () => {
      let stopped = false;
      server.use(
        http.get(`${API}/admin/sessions`, () => HttpResponse.json(stopped ? f.adminSessions() : one())),
        http.delete(`${API}/admin/sessions/:id`, () => {
          stopped = true;
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const rec = recordRequests();
      const { user } = renderSessions();
      await user.click(await screen.findByRole('button', { name: 'Detener la reproducción de Dune' }));
      expect(await screen.findByText('No hay ninguna reproducción en curso.')).toBeInTheDocument();
      expect(rec.find('DELETE', '/api/admin/sessions/a')).toHaveLength(1);
      expect(toastRegion()).toHaveTextContent('Reproducción detenida.');
    });

    it('a locked admin gets the keypad, then the DELETE is retried', async () => {
      let deletes = 0;
      server.use(
        http.get(`${API}/admin/sessions`, () => HttpResponse.json(one())),
        http.delete(`${API}/admin/sessions/:id`, () =>
          ++deletes === 1 ? apiError(401, 'admin_locked') : new HttpResponse(null, { status: 204 }),
        ),
      );
      const { user } = renderSessions();
      await user.click(await screen.findByRole('button', { name: 'Detener la reproducción de Dune' }));
      await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await user.keyboard('1234');
      await waitFor(() => expect(deletes).toBe(2));
      await waitFor(() => expect(toastRegion()).toHaveTextContent('Reproducción detenida.'));
    });

    it('a 404 session_not_found behaves like success', async () => {
      server.use(
        http.get(`${API}/admin/sessions`, () => HttpResponse.json(one())),
        http.delete(`${API}/admin/sessions/:id`, () => apiError(404, 'session_not_found')),
      );
      const rec = recordRequests();
      const { user } = renderSessions();
      await user.click(await screen.findByRole('button', { name: 'Detener la reproducción de Dune' }));
      await waitFor(() => expect(toastRegion()).toHaveTextContent('Reproducción detenida.'));
      await waitFor(() => expect(rec.find('GET', '/api/admin/sessions').length).toBeGreaterThanOrEqual(2));
    });

    it('another error toasts its message and keeps the row', async () => {
      server.use(
        http.get(`${API}/admin/sessions`, () => HttpResponse.json(one())),
        http.delete(`${API}/admin/sessions/:id`, () => apiError(500, 'internal_error')),
      );
      const { user } = renderSessions();
      await user.click(await screen.findByRole('button', { name: 'Detener la reproducción de Dune' }));
      await waitFor(() => expect(toastRegion().textContent).not.toBe(''));
      expect(toastRegion()).not.toHaveTextContent('Reproducción detenida.');
      expect(screen.getByRole('button', { name: 'Detener la reproducción de Dune' })).toBeEnabled();
    });

    it('the button is disabled while its request is in flight', async () => {
      let release!: () => void;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      server.use(
        http.get(`${API}/admin/sessions`, () => HttpResponse.json(one())),
        http.delete(`${API}/admin/sessions/:id`, async () => {
          await gate;
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { user } = renderSessions();
      const button = await screen.findByRole('button', { name: 'Detener la reproducción de Dune' });
      await user.click(button);
      await waitFor(() => expect(button).toBeDisabled());
      release();
      await waitFor(() => expect(toastRegion()).toHaveTextContent('Reproducción detenida.'));
    });
  });

  describe('polling', () => {
    it('refetches every 3 s while open and stops when the tab is left', async () => {
      useFakePlayerTimers();
      const rec = recordRequests();
      const { router } = renderSessions({ userOptions: { advanceTimers: vi.advanceTimersByTime } });
      await until(() => expect(screen.getByText('No hay ninguna reproducción en curso.')).toBeInTheDocument());
      const gets = () => rec.find('GET', '/api/admin/sessions').length;
      expect(gets()).toBe(1);
      await advance(3100);
      await until(() => expect(gets()).toBe(2));
      await advance(3000);
      await until(() => expect(gets()).toBe(3));
      await router.navigate('/admin/profiles');
      await until(() => expect(screen.queryByText('No hay ninguna reproducción en curso.')).not.toBeInTheDocument());
      const before = gets();
      await advance(10_000);
      expect(gets()).toBe(before);
    });
  });
});
