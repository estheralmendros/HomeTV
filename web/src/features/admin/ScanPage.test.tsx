import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { ScanStatus } from '@/api/types';
import { adminUnlock } from '@/auth/adminUnlock';
import * as f from '../../../tests/fixtures';
import { advance, until } from '../../../tests/flush';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { useFakePlayerTimers } from '../../../tests/player';
import { createTestQueryClient, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';

const minutesAgo = (n: number) => new Date(Date.now() - n * 60_000).toISOString();

function renderScan(options: ProviderOptions = {}) {
  return renderApp({ route: '/admin/scan', profile: f.adminProfile(), adminUnlock: f.unlockResponse(), ...options });
}

/** Serves GET /api/admin/scan from a list (the last answer repeats). */
function serveScans(...answers: Array<ScanStatus | Response>) {
  let n = 0;
  server.use(
    http.get(`${API}/admin/scan`, () => {
      const answer = answers[Math.min(n, answers.length - 1)]!;
      n += 1;
      return answer instanceof Response ? answer : HttpResponse.json(answer);
    }),
  );
}

const card = () => screen.getByRole('region', { name: 'Escaneo de la biblioteca' });
const countValue = (label: string) => within(card()).getByText(label).nextElementSibling?.textContent;
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });

const running = (overrides: Partial<ScanStatus> = {}) =>
  f.scanStatus({ state: 'running', phase: 'probing', finished_at: null, started_at: minutesAgo(2), progress: { processed: 230, total: 1012 }, ...overrides });

describe('ScanPage', () => {
  it('shows the card of the last run: state, trigger, phase, times, counts with their meanings', async () => {
    serveScans(f.scanStatus({ started_at: minutesAgo(10), finished_at: minutesAgo(2) }));
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(within(card()).getByRole('heading', { name: 'Escaneo de la biblioteca' })).toBeInTheDocument();
    expect(screen.getByText('Fase: Terminado')).toBeInTheDocument();
    expect(screen.getByText('Empezó hace 10 minutos')).toBeInTheDocument();
    expect(screen.getByText('Terminó hace 2 minutos')).toBeInTheDocument();
    expect(screen.getByText('1.012 / 1.012')).toBeInTheDocument();
    expect(within(card()).getByRole('progressbar', { name: 'Progreso del escaneo' })).toHaveAttribute('aria-valuenow', '100');
    expect(countValue('Encontrados')).toBe('1.012');
    expect(countValue('Nuevos')).toBe('240');
    expect(countValue('Modificados')).toBe('2');
    expect(countValue('Sin cambios')).toBe('769');
    expect(countValue('Ignorados')).toBe('31');
    expect(countValue('Sin identificar')).toBe('4');
    expect(countValue('Ausentes')).toBe('3');
    expect(countValue('Recuperados')).toBe('0');
    expect(countValue('Eliminados')).toBe('1');
    expect(within(card()).getByText('Ausentes')).toHaveAttribute(
      'title',
      'Archivos que no se han encontrado en este escaneo. Están ocultos, pero se conserva su historial.',
    );
    expect(within(card()).getByText('Recuperados')).toHaveAttribute('title', 'Archivos ausentes que se han vuelto a encontrar.');
    expect(within(card()).getByText('Eliminados')).toHaveAttribute(
      'title',
      'Archivos ausentes durante más tiempo que el periodo de gracia, ya borrados.',
    );
    expect(
      screen.getByText(
        'Faltan 3 archivos. Si hay un disco desconectado, vuelve a conectarlo y escanea de nuevo. No se borra nada durante unos días.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Escanear ahora' })).toBeEnabled();
    expect(document.title).toBe('Administración · HomeTV');
  });

  it('no missing warning when nothing is missing; an idle server shows "Inactivo" and no counts', async () => {
    serveScans(f.idleScanStatus());
    renderScan();
    await screen.findByText('Inactivo');
    expect(screen.queryByText('Encontrados')).not.toBeInTheDocument();
    expect(screen.queryByText(/^Faltan/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Fase/)).not.toBeInTheDocument();
  });

  it('a running scan: indeterminate progress while total is null, Scan now disabled', async () => {
    serveScans(running({ trigger: 'startup', phase: 'walking', progress: { processed: null, total: null } }));
    renderScan();
    await screen.findByText('En curso (al iniciar)');
    expect(screen.getByText('Fase: Recorriendo carpetas')).toBeInTheDocument();
    expect(screen.getByText('Empezó hace 2 minutos')).toBeInTheDocument();
    const bar = within(card()).getByRole('progressbar', { name: 'Progreso del escaneo' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(screen.getByRole('button', { name: 'Escanear ahora' })).toBeDisabled();
  });

  it('a scan started by a library folder change names its trigger', async () => {
    serveScans(running({ trigger: 'libraries', phase: 'walking', progress: { processed: null, total: null } }));
    renderScan();
    await screen.findByText('En curso (cambio de carpetas)');
  });

  it('errors (n) list the raw server lines (max 50) and a failed run shows its raw error', async () => {
    const errors = Array.from({ length: 60 }, (_, i) => `probe_failed: /media/films/broken-${i}.mkv`);
    serveScans(f.scanStatus({ state: 'failed', errors, error: 'root_unavailable: /media/films2' }));
    const { user } = renderScan();
    await screen.findByText('Fallido (manual)');
    expect(screen.getByText('root_unavailable: /media/films2')).toBeInTheDocument();
    await user.click(screen.getByText('Errores (60)'));
    const list = screen.getByRole('list', { name: 'Errores (60)' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(50);
    expect(within(list).getByText('probe_failed: /media/films/broken-0.mkv')).toBeInTheDocument();
  });

  it('warns when TMDB is not configured', async () => {
    renderScan({ info: f.info({ tmdb_configured: false }) });
    expect(
      await screen.findByText('No hay ninguna clave de API de TMDB configurada. Los contenidos no tendrán carteles ni metadatos.'),
    ).toBeInTheDocument();
  });

  it('Scan now → 202 starts polling every 2 s until completed, then invalidates the catalog and toasts the summary', async () => {
    useFakePlayerTimers();
    serveScans(
      f.scanStatus({ started_at: minutesAgo(60), finished_at: minutesAgo(59), counts: f.scanCounts({ missing: 0 }) }),
      running({ progress: { processed: 500, total: 1012 } }),
      f.scanStatus({ started_at: minutesAgo(1), finished_at: minutesAgo(0) }),
    );
    server.use(http.post(`${API}/admin/scan`, () => HttpResponse.json(running({ phase: 'walking', progress: { processed: null, total: null } }), { status: 202 })));
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.home(), f.homeResponse());
    queryClient.setQueryData(queryKeys.film(12), f.filmDetail());
    queryClient.setQueryData(queryKeys.admin.unmatched({ page: 1 }), f.page([f.unmatchedEntry()]));
    const rec = recordRequests();
    renderScan({ queryClient });
    await until(() => expect(screen.getByText('Completado (manual)')).toBeInTheDocument());
    const gets = () => rec.find('GET', '/api/admin/scan').length;
    expect(gets()).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Escanear ahora' }));
    await until(() => expect(screen.getByText('En curso (manual)')).toBeInTheDocument());
    expect(rec.find('POST', '/api/admin/scan')).toHaveLength(1);
    expect(rec.find('POST', '/api/admin/scan')[0]!.headers.get('x-admin-token')).toBe('admin-token-1');
    expect(screen.getByRole('button', { name: 'Escanear ahora' })).toBeDisabled();
    expect(gets()).toBe(1);

    await advance(2000);
    await until(() => expect(screen.getByText('500 / 1.012')).toBeInTheDocument());
    expect(gets()).toBe(2);
    expect(queryClient.getQueryState(queryKeys.home())?.isInvalidated).toBe(false);

    await advance(2000);
    await until(() => expect(screen.getByText('Completado (manual)')).toBeInTheDocument());
    expect(gets()).toBe(3);
    await until(() =>
      expect(toastRegion()).toHaveTextContent(
        'Escaneo terminado: 240 nuevos, 2 modificados, 1 eliminado, 4 sin identificar Ahora faltan 3 archivos: ¿hay algún disco desconectado?',
      ),
    );
    expect(queryClient.getQueryState(queryKeys.home())?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKeys.film(12))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKeys.admin.unmatched({ page: 1 }))?.isInvalidated).toBe(true);
    expect(screen.getByRole('button', { name: 'Escanear ahora' })).toBeEnabled();

    // Polling stopped.
    await advance(10_000);
    expect(gets()).toBe(3);
  });

  it('the finish toast lists only non-zero counts, and "Sin cambios" when all are 0', async () => {
    useFakePlayerTimers();
    serveScans(
      running(),
      f.scanStatus({ counts: f.scanCounts({ new: 0, changed: 0, restored: 0, removed: 0, unmatched: 0, missing: 0 }) }),
    );
    renderScan();
    await until(() => expect(screen.getByText('En curso (manual)')).toBeInTheDocument());
    await advance(2000);
    await until(() => expect(toastRegion()).toHaveTextContent('Escaneo terminado: Sin cambios'));
    expect(toastRegion()).not.toHaveTextContent('faltan');
  });

  it('409 scan_in_progress shows the current run from details.scan and the toast', async () => {
    useFakePlayerTimers();
    const current = running({ id: 15, trigger: 'scheduled', progress: { processed: 10, total: 1012 } });
    serveScans(f.scanStatus(), current);
    server.use(http.post(`${API}/admin/scan`, () => apiError(409, 'scan_in_progress', { details: { scan: current } })));
    renderScan();
    await until(() => expect(screen.getByText('Completado (manual)')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Escanear ahora' }));
    await until(() => expect(screen.getByText('En curso (programado)')).toBeInTheDocument());
    expect(screen.getByText('10 / 1.012')).toBeInTheDocument();
    expect(toastRegion()).toHaveTextContent('Ya hay un escaneo en curso.');
  });

  it('a failed run after polling toasts the failure', async () => {
    useFakePlayerTimers();
    serveScans(running(), f.scanStatus({ state: 'failed', error: 'root_unavailable: /media/films2' }));
    renderScan();
    await until(() => expect(screen.getByText('En curso (manual)')).toBeInTheDocument());
    await advance(2000);
    await until(() => expect(toastRegion()).toHaveTextContent('El escaneo ha fallado.'));
    expect(screen.getByText('Fallido (manual)')).toBeInTheDocument();
  });

  it('polling answered 401 admin_locked pauses polling, re-prompts the keypad, then retries with the new token', async () => {
    useFakePlayerTimers();
    serveScans(running(), apiError(401, 'admin_locked'), running({ progress: { processed: 900, total: 1012 } }), f.scanStatus());
    server.use(http.post(`${API}/admin/unlock`, () => HttpResponse.json(f.unlockResponse({ admin_token: 'admin-token-2' }))));
    const rec = recordRequests();
    renderScan();
    await until(() => expect(screen.getByText('En curso (manual)')).toBeInTheDocument());
    await advance(2000);
    await until(() => expect(screen.getByRole('dialog', { name: 'Introduce el PIN de administrador' })).toBeInTheDocument());
    expect(adminUnlock.getAdminToken()).toBeNull();
    // No session loss: still on the admin page, no picker.
    expect(screen.getByText('En curso (manual)')).toBeInTheDocument();

    await advance(10_000);
    expect(rec.find('GET', '/api/admin/scan')).toHaveLength(2);

    for (const key of '1234') fireEvent.keyDown(document, { key });
    await until(() => expect(screen.getByText('900 / 1.012')).toBeInTheDocument());
    const tokens = rec.find('GET', '/api/admin/scan').map((c) => c.headers.get('x-admin-token'));
    expect(tokens).toEqual(['admin-token-1', 'admin-token-1', 'admin-token-2']);
    await advance(2000);
    await until(() => expect(screen.getByText('Completado (manual)')).toBeInTheDocument());
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
  });

  it('Scan now answered 401 admin_locked re-prompts the keypad and retries the POST once', async () => {
    // A stateful server: once the scan starts, GET answers the running scan too.
    let posts = 0;
    let current = f.scanStatus();
    server.use(
      http.get(`${API}/admin/scan`, () => HttpResponse.json(current)),
      http.post(`${API}/admin/scan`, () => {
        if (++posts === 1) return apiError(401, 'admin_locked');
        current = running({ phase: 'walking' });
        return HttpResponse.json(current, { status: 202 });
      }),
    );
    const { user } = renderScan();
    await screen.findByText('Completado (manual)');
    const rec = recordRequests();
    await user.click(screen.getByRole('button', { name: 'Escanear ahora' }));
    await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
    await user.keyboard('1234');
    await screen.findByText('En curso (manual)');
    expect(rec.find('POST', '/api/admin/scan')).toHaveLength(2);
    expect(rec.find('POST', '/api/admin/unlock')).toHaveLength(1);
  });
});

describe('ScanPage states', () => {
  it('a skeleton while loading, then an error with [Reintentar] that refetches', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/admin/scan`, () => (++calls === 1 ? apiError(500, 'internal_error') : HttpResponse.json(f.scanStatus()))),
    );
    renderScan();
    expect(screen.getByRole('progressbar', { name: 'Cargando…' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Se ha producido un error inesperado (internal_error).');
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('region', { name: 'Escaneo de la biblioteca' })).toBeInTheDocument();
  });
});

describe('scan polling interval', () => {
  it('is not active for a finished scan (no timers firing GETs)', async () => {
    useFakePlayerTimers();
    const rec = recordRequests();
    renderScan();
    await until(() => expect(screen.getByText('Completado (manual)')).toBeInTheDocument());
    await advance(10_000);
    expect(rec.find('GET', '/api/admin/scan')).toHaveLength(1);
    vi.useRealTimers();
  });
});

const WARNING = String.fromCodePoint(0x26a0, 0xfe0e);
const scopeLine = () => within(card()).getByText(/^Carpetas:/);
const watcherLine = () => within(card()).getByText(/^Detección de cambios:/);
const watchRun = (scope: ScanStatus['scope']) => f.scanStatus({ trigger: 'watch', scope });
const letters = 'ABCDEFGHIJKL'.split('');

describe('ScanPage change-triggered runs (scope line)', () => {
  it('names the "cambio detectado" trigger and lists the scanned folders, each titled with its full path', async () => {
    serveScans(
      watchRun([
        f.scanScope({ root: '/media/MKV', path: '/media/MKV', recursive: false }),
        f.scanScope({ root: '/media/películas', path: '/media/películas/A', recursive: true }),
      ]),
    );
    renderScan();
    await screen.findByText('Completado (cambio detectado)');
    expect(scopeLine().textContent).toBe('Carpetas: MKV (archivos sueltos), películas/A');
    expect(within(scopeLine()).getByText('películas/A')).toHaveAttribute('title', '/media/películas/A');
    expect(within(scopeLine()).getByText('MKV (archivos sueltos)')).toHaveAttribute('title', '/media/MKV');
  });

  it('a whole-root scope reads "(toda la carpeta)"', async () => {
    serveScans(watchRun([f.scanScope({ root: '/media/series', path: '/media/series', recursive: true })]));
    renderScan();
    await screen.findByText('Completado (cambio detectado)');
    expect(scopeLine().textContent).toBe('Carpetas: series (toda la carpeta)');
  });

  it('12 folders: the first 5, then "y 7 más" without a comma before "y"', async () => {
    serveScans(watchRun(letters.map((l) => f.scanScope({ path: `/media/películas/${l}` }))));
    renderScan();
    await screen.findByText('Completado (cambio detectado)');
    expect(scopeLine().textContent).toBe('Carpetas: películas/A, películas/B, películas/C, películas/D, películas/E y 7 más');
    expect(within(scopeLine()).queryByText('películas/F')).not.toBeInTheDocument();
  });

  it('exactly 5 folders: no "más" suffix', async () => {
    serveScans(watchRun(letters.slice(0, 5).map((l) => f.scanScope({ path: `/media/películas/${l}` }))));
    renderScan();
    await screen.findByText('Completado (cambio detectado)');
    expect(scopeLine().textContent).toBe('Carpetas: películas/A, películas/B, películas/C, películas/D, películas/E');
  });

  it.each([
    ['null', null],
    ['empty', []],
  ])('no folders line when the scope is %s', async (_name, scope) => {
    serveScans(f.scanStatus({ scope }));
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(within(card()).queryByText(/Carpetas:/)).not.toBeInTheDocument();
  });
});

describe('ScanPage change detection (watcher line)', () => {
  it.each([
    ['active', 'Detección de cambios: activa'],
    ['starting', 'Detección de cambios: iniciando…'],
    ['disabled', 'Detección de cambios: desactivada'],
  ] as const)('%s: a plain line', async (state, text) => {
    serveScans(f.scanStatus({ watcher: f.watcherStatus({ state }) }));
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(watcherLine().textContent).toBe(text);
    expect(within(card()).queryByText(/No disponible en/)).not.toBeInTheDocument();
    expect(within(card()).queryByText('Detalles técnicos')).not.toBeInTheDocument();
  });

  it('error with a detail: a warning line and a "Detalles técnicos" disclosure with the raw detail', async () => {
    const detail = 'inotify watch limit reached (fs.inotify.max_user_watches)';
    serveScans(f.scanStatus({ watcher: f.watcherStatus({ state: 'error', detail }) }));
    const { user } = renderScan();
    await screen.findByText('Completado (manual)');
    expect(watcherLine().textContent).toBe(`${WARNING} Detección de cambios: con errores`);
    const summary = within(card()).getByText('Detalles técnicos');
    const disclosure = summary.closest('details')!;
    expect(disclosure).not.toHaveAttribute('open');
    await user.click(summary);
    expect(disclosure).toHaveAttribute('open');
    expect(within(disclosure).getByText(detail).tagName).toBe('CODE');
  });

  it('unavailable roots on Linux: a warning line and the folders, no disclosure without a detail', async () => {
    serveScans(
      f.scanStatus({
        watcher: f.watcherStatus({ state: 'unavailable', unavailable_roots: ['/media/MKV', '/media/películas', '/media/series'] }),
      }),
    );
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(watcherLine().textContent).toBe(`${WARNING} Detección de cambios: no disponible`);
    expect(within(card()).getByText('No disponible en: /media/MKV, /media/películas, /media/series')).toBeInTheDocument();
    expect(within(card()).queryByText('Detalles técnicos')).not.toBeInTheDocument();
  });

  it('active with one unavailable root: the plain line and "No disponible en"', async () => {
    serveScans(f.scanStatus({ watcher: f.watcherStatus({ unavailable_roots: ['/media/series'] }) }));
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(watcherLine().textContent).toBe('Detección de cambios: activa');
    expect(within(card()).getByText('No disponible en: /media/series')).toBeInTheDocument();
  });

  it('a non-Linux host: "no disponible" plus the disclosure, no "No disponible en" line', async () => {
    serveScans(f.scanStatus({ watcher: f.watcherStatus({ state: 'unavailable', detail: 'inotify requires Linux' }) }));
    renderScan();
    await screen.findByText('Completado (manual)');
    expect(watcherLine().textContent).toBe(`${WARNING} Detección de cambios: no disponible`);
    const disclosure = within(card()).getByText('Detalles técnicos').closest('details')!;
    expect(within(disclosure).getByText('inotify requires Linux')).toBeInTheDocument();
    expect(within(card()).queryByText(/No disponible en/)).not.toBeInTheDocument();
  });

  it('an idle server still shows the watcher line', async () => {
    serveScans(f.idleScanStatus({ watcher: f.watcherStatus({ state: 'disabled' }) }));
    renderScan();
    await screen.findByText('Inactivo');
    expect(watcherLine().textContent).toBe('Detección de cambios: desactivada');
  });

  it('"iniciando…" keeps polling every 2 s until change detection is active, then stops (no toast)', async () => {
    useFakePlayerTimers();
    const starting = f.scanStatus({ watcher: f.watcherStatus({ state: 'starting' }) });
    serveScans(starting, starting, f.scanStatus());
    const rec = recordRequests();
    renderScan();
    await until(() => expect(watcherLine().textContent).toBe('Detección de cambios: iniciando…'));
    const gets = () => rec.find('GET', '/api/admin/scan').length;
    expect(gets()).toBe(1);
    await advance(2000);
    await until(() => expect(gets()).toBe(2));
    expect(watcherLine().textContent).toBe('Detección de cambios: iniciando…');
    await advance(2000);
    await until(() => expect(watcherLine().textContent).toBe('Detección de cambios: activa'));
    expect(gets()).toBe(3);
    await advance(10_000);
    expect(gets()).toBe(3);
    expect(toastRegion()).not.toHaveTextContent('Escaneo terminado');
  });
});
