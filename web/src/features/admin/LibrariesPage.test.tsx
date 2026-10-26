import { screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { LibrariesPlan, LibrariesResponse } from '@/api/types';
import { adminUnlock } from '@/auth/adminUnlock';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import { currentUrl, renderApp, type ProviderOptions } from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';

type User = ReturnType<typeof renderApp>['user'];

function renderLibraries(options: ProviderOptions = {}) {
  return renderApp({ route: '/admin/libraries', profile: f.adminProfile(), adminUnlock: f.unlockResponse(), ...options });
}

function serveLibraries(response: LibrariesResponse) {
  server.use(http.get(`${API}/admin/libraries`, () => HttpResponse.json(response)));
}

/** Serves POST /plan from a list (the last answer repeats). */
function servePlans(...answers: Array<LibrariesPlan | Response>) {
  let n = 0;
  server.use(
    http.post(`${API}/admin/libraries/plan`, () => {
      const answer = answers[Math.min(n, answers.length - 1)]!;
      n += 1;
      return answer instanceof Response ? answer : HttpResponse.json(answer);
    }),
  );
}

const section = (name: 'Películas' | 'Series') => screen.getByRole('region', { name });
const row = (path: string, library: 'Películas' | 'Series' = 'Películas') =>
  within(section(library)).getByRole('listitem', { name: path });
const saveButton = () => screen.getAllByRole('button', { name: 'Guardar cambios' })[0]!;
const discardButton = () => screen.getByRole('button', { name: 'Descartar' });
const toastRegion = () => screen.getByRole('status', { name: 'Notificaciones' });
const confirmDialog = (name: string | RegExp) => screen.findByRole('dialog', { name });
const GRACE_TEXT = /volverán con su historial de reproducción/;

async function loaded() {
  await screen.findByRole('listitem', { name: '/media/Films' });
}

async function openAdd(user: User, library: 'Películas' | 'Series') {
  await user.click(within(section(library)).getByRole('button', { name: 'Añadir carpeta' }));
  return screen.findByRole('dialog', { name: `Añadir carpeta a ${library}` });
}

async function addTyped(user: User, library: 'Películas' | 'Series', path: string) {
  const dialog = await openAdd(user, library);
  await user.type(within(dialog).getByRole('textbox', { name: 'Ruta de la carpeta en el servidor' }), path);
  await user.click(within(dialog).getByRole('button', { name: 'Añadir' }));
}

describe('LibrariesPage', () => {
  describe('rows and draft', () => {
    it('lists both libraries in server order with status chips and title counts', async () => {
      serveLibraries(
        f.librariesResponse({
          films: [
            f.libraryFolder({ path: '/media/Films', title_count: 412 }),
            f.libraryFolder({ path: '/media/Films4K', status: 'not_found', title_count: 37 }),
            f.libraryFolder({ path: '/media/Privado', status: 'not_readable', title_count: 1 }),
          ],
          shows: [],
        }),
      );
      renderLibraries();
      await loaded();
      const films = within(section('Películas')).getAllByRole('listitem');
      expect(films.map((li) => li.getAttribute('aria-labelledby') && within(li).getByText(/^\/media/).textContent)).toEqual([
        '/media/Films', '/media/Films4K', '/media/Privado',
      ]);
      expect(within(row('/media/Films')).getByText('Disponible')).toBeInTheDocument();
      expect(within(row('/media/Films')).getByText('412 títulos')).toBeInTheDocument();
      expect(within(row('/media/Films4K')).getByText('No encontrada')).toBeInTheDocument();
      expect(within(row('/media/Privado')).getByText('Sin permiso')).toBeInTheDocument();
      expect(within(row('/media/Privado')).getByText('1 título')).toBeInTheDocument();
      expect(within(row('/media/Films')).getByRole('button', { name: 'Quitar /media/Films' })).toBeInTheDocument();
      expect(within(section('Series')).getByText('Sin carpetas')).toBeInTheDocument();
      expect(within(section('Series')).getByRole('button', { name: 'Añadir carpeta' })).toBeInTheDocument();
      expect(saveButton()).toBeDisabled();
      expect(discardButton()).toBeDisabled();
    });

    it('add, remove an added row, remove/undo a saved row, discard restores the server lists', async () => {
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();

      await addTyped(user, 'Películas', '/media/Documentales');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(within(row('/media/Documentales')).getByText('Nueva')).toBeInTheDocument();
      expect(saveButton()).toBeEnabled();
      expect(discardButton()).toBeEnabled();
      await user.click(within(row('/media/Documentales')).getByRole('button', { name: 'Quitar /media/Documentales' }));
      expect(within(section('Películas')).queryByRole('listitem', { name: '/media/Documentales' })).not.toBeInTheDocument();
      expect(saveButton()).toBeDisabled();

      await user.click(within(row('/media/TV', 'Series')).getByRole('button', { name: 'Quitar /media/TV' }));
      expect(within(row('/media/TV', 'Series')).getByText('Se quitará')).toBeInTheDocument();
      expect(within(row('/media/TV', 'Series')).queryByText('Disponible')).not.toBeInTheDocument();
      await user.click(within(row('/media/TV', 'Series')).getByRole('button', { name: 'Deshacer quitar /media/TV' }));
      expect(within(row('/media/TV', 'Series')).getByText('Disponible')).toBeInTheDocument();
      expect(saveButton()).toBeDisabled();

      await user.click(within(row('/media/Films')).getByRole('button', { name: 'Quitar /media/Films' }));
      await addTyped(user, 'Series', '/media/Anime');
      await user.click(discardButton());
      expect(within(row('/media/Films')).getByText('Disponible')).toBeInTheDocument();
      expect(within(section('Series')).queryByRole('listitem', { name: '/media/Anime' })).not.toBeInTheDocument();
      expect(saveButton()).toBeDisabled();
      expect(rec.find('POST', '/api/admin/libraries/plan')).toHaveLength(0);
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);
    });

    it('typed path checks: not absolute and duplicate, without a request', async () => {
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Series');
      const input = within(dialog).getByRole('textbox', { name: 'Ruta de la carpeta en el servidor' });
      expect(input).toHaveAttribute('placeholder', '/media/Peliculas');
      expect(within(dialog).getByRole('button', { name: 'Añadir' })).toBeDisabled();
      await user.type(input, '   ');
      expect(within(dialog).getByRole('button', { name: 'Añadir' })).toBeDisabled();
      await user.clear(input);
      await user.type(input, 'media/x{Enter}');
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Escribe la ruta completa; tiene que empezar por /.');
      await user.clear(input);
      await user.type(input, '/media/Films/');
      await user.click(within(dialog).getByRole('button', { name: 'Añadir' }));
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Esta carpeta ya está en la lista.');
      await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(saveButton()).toBeDisabled();
      await rec.settled();
      expect(rec.calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
    });

    it('the ignored-config notice names the ignored variables; none when the list is empty', async () => {
      serveLibraries(f.librariesResponse({ ignored_env: ['HOMETV_FILMS_PATHS', 'HOMETV_SHOWS_PATHS'] }));
      renderLibraries();
      await loaded();
      expect(screen.getByRole('note')).toHaveTextContent(
        'HomeTV ya no usa HOMETV_FILMS_PATHS, HOMETV_SHOWS_PATHS para elegir las carpetas: ahora se gestionan desde esta pantalla. Puedes quitar esa configuración.',
      );
    });

    it('no notice when nothing is ignored', async () => {
      renderLibraries();
      await loaded();
      expect(screen.queryByRole('note')).not.toBeInTheDocument();
    });

    it('a failed GET shows the error with Reintentar', async () => {
      let calls = 0;
      server.use(
        http.get(`${API}/admin/libraries`, () => (++calls === 1 ? apiError(500, 'internal') : HttpResponse.json(f.librariesResponse()))),
      );
      const { user } = renderLibraries();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Se ha producido un error inesperado (internal).');
      await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));
      await loaded();
    });

    it('a locked admin gets the keypad before the GET is sent', async () => {
      const rec = recordRequests();
      const { user } = renderLibraries({ adminUnlock: null });
      await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await rec.settled();
      expect(rec.find('GET', '/api/admin/libraries')).toHaveLength(0);
      await user.keyboard('1234');
      await loaded();
      expect(rec.find('GET', '/api/admin/libraries')).toHaveLength(1);
    });

    it('a viewer opening /admin/libraries sees "Solo para administradores"', async () => {
      renderApp({ route: '/admin/libraries', profile: f.profile() });
      expect(await screen.findByText('Solo para administradores')).toBeInTheDocument();
    });

    it('phone (360 px): rows stack and the buttons are full width', async () => {
      setViewportWidth(360);
      const { user } = renderLibraries();
      await loaded();
      const page = screen.getByTestId('libraries-page');
      expect(page).toHaveAttribute('data-layout', 'phone');
      // The path has its own line; chip, count and button share the next one.
      const r = row('/media/Films');
      expect(within(r).getByTestId('row-path')).toHaveTextContent('/media/Films');
      expect(within(within(r).getByTestId('row-meta')).getByText('412 títulos')).toBeInTheDocument();
      expect(within(r).getByTestId('row-meta')).toContainElement(within(r).getByRole('button', { name: 'Quitar /media/Films' }));
      expect(within(section('Series')).getByRole('button', { name: 'Añadir carpeta' })).toHaveAttribute('data-full-width', 'true');
      expect(saveButton()).toHaveAttribute('data-full-width', 'true');
      expect(discardButton()).toHaveAttribute('data-full-width', 'true');
      // Stacked save bar: "Guardar cambios" first.
      expect(saveButton().compareDocumentPosition(discardButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      const dialog = await openAdd(user, 'Películas');
      expect(dialog.querySelector('form')).toHaveAttribute('data-layout', 'phone');
    });

    it('desktop keeps one line per row', async () => {
      renderLibraries();
      await loaded();
      expect(screen.getByTestId('libraries-page')).toHaveAttribute('data-layout', 'desktop');
      expect(saveButton()).not.toHaveAttribute('data-full-width');
    });
  });

  describe('folder browser', () => {
    it('lists the subfolders of /media, navigates, goes up, chooses a folder and adds it', async () => {
      server.use(
        http.get(`${API}/admin/libraries/browse`, ({ request }) => {
          const path = new URL(request.url).searchParams.get('path');
          if (path === '/media/Disco2')
            return HttpResponse.json(
              f.browseResponse({
                path: '/media/Disco2',
                parent: '/media',
                entries: [
                  { name: 'Documentales', path: '/media/Disco2/Documentales' },
                  { name: 'Vacía', path: '/media/Disco2/Vacía' },
                ],
              }),
            );
          if (path?.startsWith('/media/Disco2/'))
            return HttpResponse.json(f.browseResponse({ path, parent: '/media/Disco2', entries: [] }));
          return HttpResponse.json(f.browseResponse());
        }),
      );
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Películas');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      expect(await within(browser).findByText('Carpeta actual: /media')).toBeInTheDocument();
      expect(within(browser).getByRole('button', { name: 'Subir' })).toBeDisabled();
      expect(within(browser).getByRole('button', { name: 'Films' })).toBeInTheDocument();
      expect(rec.find('GET', '/api/admin/libraries/browse')[0]!.url.searchParams.has('path')).toBe(false);

      await user.click(within(browser).getByRole('button', { name: 'Disco2' }));
      expect(await within(browser).findByText('Carpeta actual: /media/Disco2')).toBeInTheDocument();
      expect(rec.find('GET', '/api/admin/libraries/browse').at(-1)!.url.searchParams.get('path')).toBe('/media/Disco2');
      await user.click(within(browser).getByRole('button', { name: 'Vacía' }));
      expect(await within(browser).findByText('No hay subcarpetas')).toBeInTheDocument();
      await user.click(within(browser).getByRole('button', { name: 'Subir' }));
      expect(await within(browser).findByText('Carpeta actual: /media/Disco2')).toBeInTheDocument();

      // Typing does not move the browser.
      const input = within(dialog).getByRole('textbox', { name: 'Ruta de la carpeta en el servidor' });
      await user.type(input, '/media/Otra');
      expect(within(browser).getByText('Carpeta actual: /media/Disco2')).toBeInTheDocument();

      await user.click(within(browser).getByRole('button', { name: 'Documentales' }));
      await within(browser).findByText('Carpeta actual: /media/Disco2/Documentales');
      await user.click(within(browser).getByRole('button', { name: 'Elegir esta carpeta' }));
      expect(input).toHaveValue('/media/Disco2/Documentales');
      await user.click(within(dialog).getByRole('button', { name: 'Añadir' }));
      expect(within(row('/media/Disco2/Documentales')).getByText('Nueva')).toBeInTheDocument();
    });

    it('a folder gone (404) shows "Esta carpeta ya no existe." with "Volver al inicio"', async () => {
      server.use(
        http.get(`${API}/admin/libraries/browse`, ({ request }) =>
          new URL(request.url).searchParams.get('path') === '/media/Disco2'
            ? apiError(404, 'folder_not_found')
            : HttpResponse.json(f.browseResponse()),
        ),
      );
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Películas');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      await user.click(await within(browser).findByRole('button', { name: 'Disco2' }));
      expect(await within(browser).findByRole('alert')).toHaveTextContent('Esta carpeta ya no existe.');
      await user.click(within(browser).getByRole('button', { name: 'Volver al inicio' }));
      expect(await within(browser).findByRole('button', { name: 'Disco2' })).toBeInTheDocument();
      expect(within(browser).getByText('Carpeta actual: /media')).toBeInTheDocument();
    });

    it('an unreadable subfolder (403) offers "Volver" (back to its parent) and "Volver al inicio"; choosing is disabled', async () => {
      server.use(
        http.get(`${API}/admin/libraries/browse`, ({ request }) => {
          const path = new URL(request.url).searchParams.get('path');
          if (path === '/media/Disco2')
            return HttpResponse.json(
              f.browseResponse({ path, parent: '/media', entries: [{ name: 'Privado', path: '/media/Disco2/Privado' }] }),
            );
          if (path === '/media/Disco2/Privado') return apiError(403, 'folder_not_readable');
          return HttpResponse.json(f.browseResponse());
        }),
      );
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Películas');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      await user.click(await within(browser).findByRole('button', { name: 'Disco2' }));
      await user.click(await within(browser).findByRole('button', { name: 'Privado' }));
      expect(await within(browser).findByRole('alert')).toHaveTextContent('HomeTV no tiene permiso para leer esta carpeta.');
      expect(within(browser).getByText('Carpeta actual: /media/Disco2/Privado')).toBeInTheDocument();
      expect(within(browser).getByRole('button', { name: 'Elegir esta carpeta' })).toBeDisabled();
      expect(within(browser).getByRole('button', { name: 'Volver al inicio' })).toBeInTheDocument();
      // Typing keeps working in the error state.
      const input = within(dialog).getByRole('textbox', { name: 'Ruta de la carpeta en el servidor' });
      await user.type(input, '/media/x');
      expect(input).toHaveValue('/media/x');
      await user.click(within(browser).getByRole('button', { name: 'Volver' }));
      expect(await within(browser).findByRole('button', { name: 'Privado' })).toBeInTheDocument();
      expect(within(browser).getByText('Carpeta actual: /media/Disco2')).toBeInTheDocument();
      expect(within(browser).queryByRole('alert')).not.toBeInTheDocument();
      expect(within(browser).getByRole('button', { name: 'Elegir esta carpeta' })).toBeEnabled();
    });

    it('a folder visited earlier and gone now offers "Volver" to the folder it came from', async () => {
      let gone = false;
      server.use(
        http.get(`${API}/admin/libraries/browse`, ({ request }) => {
          const path = new URL(request.url).searchParams.get('path');
          if (path === '/media/Disco2')
            return HttpResponse.json(
              f.browseResponse({ path, parent: '/media', entries: [{ name: 'Series', path: '/media/Disco2/Series' }] }),
            );
          if (path === '/media/Disco2/Series')
            return gone
              ? apiError(404, 'folder_not_found')
              : HttpResponse.json(f.browseResponse({ path, parent: '/media/Disco2', entries: [] }));
          return HttpResponse.json(f.browseResponse());
        }),
      );
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Series');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      await user.click(await within(browser).findByRole('button', { name: 'Disco2' }));
      await user.click(await within(browser).findByRole('button', { name: 'Series' }));
      expect(await within(browser).findByText('No hay subcarpetas')).toBeInTheDocument();
      await user.click(within(browser).getByRole('button', { name: 'Subir' }));
      await within(browser).findByText('Carpeta actual: /media/Disco2');

      // The cached listing of Series shows while it refetches; the refetch fails.
      gone = true;
      await user.click(await within(browser).findByRole('button', { name: 'Series' }));
      expect(await within(browser).findByRole('alert')).toHaveTextContent('Esta carpeta ya no existe.');
      expect(within(browser).getByRole('button', { name: 'Volver al inicio' })).toBeInTheDocument();
      await user.click(within(browser).getByRole('button', { name: 'Volver' }));
      expect(await within(browser).findByRole('button', { name: 'Series' })).toBeInTheDocument();
      expect(within(browser).getByText('Carpeta actual: /media/Disco2')).toBeInTheDocument();
    });

    it('a network error on a subfolder offers the same two buttons', async () => {
      server.use(
        http.get(`${API}/admin/libraries/browse`, ({ request }) =>
          new URL(request.url).searchParams.get('path') === '/media/TV' ? HttpResponse.error() : HttpResponse.json(f.browseResponse()),
        ),
      );
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Series');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      await user.click(await within(browser).findByRole('button', { name: 'TV' }));
      expect(await within(browser).findByRole('alert')).toHaveTextContent('No se puede conectar con el servidor.');
      expect(within(browser).getByRole('button', { name: 'Volver' })).toBeInTheDocument();
      expect(within(browser).getByRole('button', { name: 'Volver al inicio' })).toBeInTheDocument();
    });

    it('the root itself failing offers "Reintentar", which refetches', async () => {
      let calls = 0;
      server.use(
        http.get(`${API}/admin/libraries/browse`, () =>
          ++calls === 1 ? apiError(409, 'browse_unavailable') : HttpResponse.json(f.browseResponse()),
        ),
      );
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Películas');
      const browser = within(dialog).getByRole('region', { name: 'Carpetas del servidor' });
      expect(await within(browser).findByRole('alert')).toHaveTextContent('No se puede explorar el servidor.');
      expect(within(browser).queryByRole('button', { name: 'Volver' })).not.toBeInTheDocument();
      expect(within(browser).queryByRole('button', { name: 'Volver al inicio' })).not.toBeInTheDocument();
      await user.click(within(browser).getByRole('button', { name: 'Reintentar' }));
      expect(await within(browser).findByRole('button', { name: 'Disco2' })).toBeInTheDocument();
    });

    it('browse root unavailable: no browse request, a note, typing still works', async () => {
      serveLibraries(f.librariesResponse({ browse_root: { path: '/media', available: false } }));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      const dialog = await openAdd(user, 'Películas');
      expect(within(dialog).getByText('No se puede explorar /media en el servidor. Escribe la ruta completa.')).toBeInTheDocument();
      expect(within(dialog).queryByRole('region', { name: 'Carpetas del servidor' })).not.toBeInTheDocument();
      await user.type(within(dialog).getByRole('textbox', { name: 'Ruta de la carpeta en el servidor' }), '/media/Docs{Enter}');
      expect(within(row('/media/Docs')).getByText('Nueva')).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('GET', '/api/admin/libraries/browse')).toHaveLength(0);
    });
  });

  describe('save', () => {
    it('only additions with an empty plan: plan, then PUT with confirm_relinks false, toast, refetch, scan card', async () => {
      let puts = 0;
      server.use(
        http.put(`${API}/admin/libraries`, () => {
          puts += 1;
          return HttpResponse.json({
            ...f.librariesResponse(),
            scan: f.scanStatus({ state: 'running', trigger: 'libraries', phase: 'walking', finished_at: null }),
          });
        }),
        http.get(`${API}/admin/scan`, () =>
          HttpResponse.json(
            puts === 0 ? f.scanStatus() : f.scanStatus({ state: 'running', trigger: 'libraries', phase: 'walking', finished_at: null }),
          ),
        ),
      );
      const rec = recordRequests();
      const { user, router } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Documentales');
      await user.click(saveButton());
      expect(await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado')).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      await rec.settled();
      const [plan] = rec.find('POST', '/api/admin/libraries/plan');
      const [put] = rec.find('PUT', '/api/admin/libraries');
      expect(plan!.body).toEqual({ films: ['/media/Films', '/media/Films4K', '/media/Documentales'], shows: ['/media/TV'] });
      expect(put!.body).toEqual({ ...(plan!.body as object), confirm_relinks: false });
      expect(rec.calls.findIndex((c) => c === plan)).toBeLessThan(rec.calls.findIndex((c) => c === put));
      await waitFor(() => expect(rec.find('GET', '/api/admin/libraries')).toHaveLength(2));
      expect(saveButton()).toBeDisabled();

      await user.click(within(screen.getByRole('navigation', { name: 'Secciones de administración' })).getByRole('link', { name: 'Escaneo' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/admin/scan'));
      expect(await screen.findByText('En curso (cambio de carpetas)')).toBeInTheDocument();
    });

    it('a save that starts no scan toasts "Carpetas guardadas"', async () => {
      server.use(http.put(`${API}/admin/libraries`, () => HttpResponse.json({ ...f.librariesResponse(), scan: null })));
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Series', '/media/Anime');
      await user.click(saveButton());
      expect(await within(toastRegion()).findByText('Carpetas guardadas')).toBeInTheDocument();
    });

    it('the PUT response seeds the libraries and scan caches and resets the draft', async () => {
      const saved = f.librariesResponse({
        films: [f.libraryFolder({ path: '/media/Films' }), f.libraryFolder({ path: '/media/Nueva', title_count: 0 })],
      });
      const scan = f.scanStatus({ state: 'running', trigger: 'libraries', phase: 'walking', finished_at: null });
      let planned = false;
      server.use(
        http.put(`${API}/admin/libraries`, () => HttpResponse.json({ ...saved, scan })),
        // The refetches after the save are held, so the caches still show what the PUT answered.
        http.get(`${API}/admin/libraries`, async () => {
          if (planned) await delay('infinite');
          return HttpResponse.json(f.librariesResponse());
        }),
        http.get(`${API}/admin/scan`, async () => {
          if (planned) await delay('infinite');
          return HttpResponse.json(f.scanStatus());
        }),
        http.post(`${API}/admin/libraries/plan`, () => {
          planned = true;
          return HttpResponse.json(f.librariesPlan());
        }),
      );
      const { user, queryClient } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Nueva');
      await user.click(saveButton());
      await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado');
      expect(queryClient.getQueryData(queryKeys.admin.scan())).toMatchObject({ state: 'running', trigger: 'libraries' });
      expect(queryClient.getQueryData(queryKeys.admin.libraries())).toMatchObject({ films: saved.films });
      expect(within(row('/media/Nueva')).getByText('0 títulos')).toBeInTheDocument();
      expect(within(row('/media/Nueva')).queryByText('Nueva')).not.toBeInTheDocument();
      expect(section('Películas').textContent).not.toContain('/media/Films4K');
    });

    it('a removal: "¿Quitar 1 carpeta?" with the server grace days; Cancelar sends nothing; "Quitar y guardar" closes and PUTs', async () => {
      serveLibraries(f.librariesResponse({ missing_grace_days: 3 }));
      servePlans(f.librariesPlan({ removed: [{ library: 'films', path: '/media/Films4K', title_count: 37 }] }));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await user.click(within(row('/media/Films4K')).getByRole('button', { name: 'Quitar /media/Films4K' }));
      await user.click(saveButton());
      let dialog = await confirmDialog('¿Quitar 1 carpeta?');
      expect(within(dialog).getByText('Películas · /media/Films4K (37 títulos)')).toBeInTheDocument();
      expect(
        within(dialog).getByText(
          'Sus títulos dejarán de verse en el catálogo ahora y se borrarán dentro de 3 días si no vuelves a añadir la carpeta. Si la vuelves a añadir antes, volverán con su historial de reproducción.',
        ),
      ).toBeInTheDocument();
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(within(row('/media/Films4K')).getByText('Se quitará')).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);

      await user.click(saveButton());
      dialog = await confirmDialog('¿Quitar 1 carpeta?');
      await user.click(within(dialog).getByRole('button', { name: 'Quitar y guardar' }));
      expect(screen.queryByRole('dialog', { name: '¿Quitar 1 carpeta?' })).not.toBeInTheDocument();
      await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado');
      await rec.settled();
      const puts = rec.find('PUT', '/api/admin/libraries');
      expect(puts).toHaveLength(1);
      expect(puts[0]!.body).toEqual({ films: ['/media/Films'], shows: ['/media/TV'], confirm_relinks: false });
      expect(rec.find('POST', '/api/admin/libraries/plan')).toHaveLength(2);
    });

    it.each([
      [1, 'Sus títulos dejarán de verse en el catálogo ahora y se borrarán dentro de 1 día si no vuelves a añadir la carpeta. Si la vuelves a añadir antes, volverán con su historial de reproducción.'],
      [0, 'Sus títulos y su historial de reproducción se borrarán en el escaneo que empieza ahora.'],
    ])('grace days %i: the removal text', async (days, text) => {
      serveLibraries(f.librariesResponse({ missing_grace_days: days }));
      servePlans(
        f.librariesPlan({
          removed: [
            { library: 'films', path: '/media/Films4K', title_count: 37 },
            { library: 'shows', path: '/media/TV', title_count: 4 },
          ],
        }),
      );
      const { user } = renderLibraries();
      await loaded();
      await user.click(within(row('/media/Films4K')).getByRole('button', { name: 'Quitar /media/Films4K' }));
      await user.click(within(row('/media/TV', 'Series')).getByRole('button', { name: 'Quitar /media/TV' }));
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Quitar 2 carpetas?');
      expect(within(dialog).getByText('Series · /media/TV (4 títulos)')).toBeInTheDocument();
      expect(within(dialog).getByText(text)).toBeInTheDocument();
    });

    it.each([
      ['422 invalid_library_folders', apiError(422, 'invalid_library_folders', { details: { folders: [{ library: 'films', path: '/media/Films', code: 'not_readable', other: null }] } }), 'HomeTV no tiene permiso para leer esta carpeta.'],
      ['409 scan_in_progress', apiError(409, 'scan_in_progress', { details: { scan: f.scanStatus({ state: 'running', finished_at: null }) } }), 'Hay un escaneo en curso; guarda cuando termine.'],
    ])('a removal answered %s: the dialog is closed, the result shows on the page, the draft is kept', async (_name, answer, text) => {
      servePlans(f.librariesPlan({ removed: [{ library: 'films', path: '/media/Films4K', title_count: 37 }] }));
      server.use(http.put(`${API}/admin/libraries`, () => answer));
      const { user } = renderLibraries();
      await loaded();
      await user.click(within(row('/media/Films4K')).getByRole('button', { name: 'Quitar /media/Films4K' }));
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Quitar 1 carpeta?');
      await user.click(within(dialog).getByRole('button', { name: 'Quitar y guardar' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(await screen.findByText(text)).toBeInTheDocument();
      expect(within(row('/media/Films4K')).getByText('Se quitará')).toBeInTheDocument();
    });

    it('a moved folder (same path): "¿Guardar estos cambios de carpetas?", the history warning first, PUT confirm_relinks true', async () => {
      servePlans(
        f.librariesPlan({
          removed: [{ library: 'films', path: '/media/Films4K', title_count: 37 }],
          moved: [{ from_library: 'films', from_path: '/media/Docs', to_library: 'shows', to_path: '/media/Docs', title_count: 5 }],
        }),
      );
      serveLibraries(f.librariesResponse({ films: [f.libraryFolder(), f.libraryFolder({ path: '/media/Films4K' }), f.libraryFolder({ path: '/media/Docs' })] }));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await user.click(within(row('/media/Docs')).getByRole('button', { name: 'Quitar /media/Docs' }));
      await user.click(within(row('/media/Films4K')).getByRole('button', { name: 'Quitar /media/Films4K' }));
      await addTyped(user, 'Series', '/media/Docs');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      const moved = within(dialog).getByText('Películas → Series · /media/Docs (5 títulos)');
      const warning = within(dialog).getByRole('alert');
      expect(warning).toHaveTextContent(
        'Se volverán a identificar en la otra biblioteca y su historial de reproducción se borrará en el escaneo que empieza ahora. No se puede recuperar.',
      );
      const removed = within(dialog).getByText('Películas · /media/Films4K (37 títulos)');
      expect(moved.compareDocumentPosition(removed) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(warning.compareDocumentPosition(removed) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }));
      await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado');
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')[0]!.body).toEqual({
        films: ['/media/Films'],
        shows: ['/media/TV', '/media/Docs'],
        confirm_relinks: true,
      });
    });

    it('a moved folder with different paths (overlap): the long item, the warning and never the grace text', async () => {
      servePlans(
        f.librariesPlan({
          moved: [{ from_library: 'shows', from_path: '/media/TV', to_library: 'films', to_path: '/media', title_count: 12 }],
        }),
      );
      serveLibraries(f.librariesResponse({ films: [], shows: [f.libraryFolder({ path: '/media/TV' })] }));
      const { user } = renderLibraries();
      await screen.findByRole('listitem', { name: '/media/TV' });
      await user.click(within(row('/media/TV', 'Series')).getByRole('button', { name: 'Quitar /media/TV' }));
      await addTyped(user, 'Películas', '/media');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      expect(within(dialog).getByText('Series · /media/TV → Películas · /media (12 títulos)')).toBeInTheDocument();
      expect(within(dialog).getByRole('alert')).toHaveTextContent(/su historial de reproducción se borrará/);
      expect(within(dialog).queryByText(GRACE_TEXT)).not.toBeInTheDocument();
    });

    it('a draft that only adds a Series folder, answered by a moved plan: the moved group only; Cancelar sends no PUT', async () => {
      servePlans(
        f.librariesPlan({
          moved: [{ from_library: 'films', from_path: '/media/Anime', to_library: 'shows', to_path: '/media/Anime', title_count: 5 }],
        }),
      );
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Series', '/media/Anime');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      expect(within(dialog).getAllByRole('listitem')).toHaveLength(1);
      expect(within(dialog).getByText('Películas → Series · /media/Anime (5 títulos)')).toBeInTheDocument();
      expect(within(dialog).queryByText(GRACE_TEXT)).not.toBeInTheDocument();
      expect(within(dialog).queryByText(/conservan su historial/)).not.toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);
      expect(within(row('/media/Anime', 'Series')).getByText('Nueva')).toBeInTheDocument();
    });

    it('wrong Series folder corrected: the relayout item and warning, no grace text, PUT confirm_relinks true, no row error', async () => {
      serveLibraries(f.librariesResponse({ shows: [f.libraryFolder({ path: '/media', title_count: 1 })] }));
      servePlans(f.librariesPlan({ relayout: [{ from_path: '/media', to_path: '/media/series', title_count: 1 }] }));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await user.click(within(row('/media', 'Series')).getByRole('button', { name: 'Quitar /media' }));
      await addTyped(user, 'Series', '/media/series');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      expect(within(dialog).getByText('Series · /media → /media/series (1 serie)')).toBeInTheDocument();
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        'Sus episodios se volverán a identificar a partir de la nueva carpeta en el escaneo que empieza ahora. Las series que salgan con otro nombre perderán su historial de reproducción; no se puede recuperar.',
      );
      expect(within(dialog).queryByText(GRACE_TEXT)).not.toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }));
      await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado');
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')[0]!.body).toEqual({
        films: ['/media/Films', '/media/Films4K'],
        shows: ['/media/series'],
        confirm_relinks: true,
      });
      expect(screen.queryByText('Revisa las carpetas marcadas.')).not.toBeInTheDocument();
    });

    it('a covered folder: kept under the new parent, with its history', async () => {
      serveLibraries(f.librariesResponse({ films: [f.libraryFolder({ path: '/media/MKV' })], shows: [] }));
      servePlans(f.librariesPlan({ covered: [{ library: 'films', path: '/media/MKV', by: '/media', title_count: 40 }] }));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await screen.findByRole('listitem', { name: '/media/MKV' });
      await user.click(within(row('/media/MKV')).getByRole('button', { name: 'Quitar /media/MKV' }));
      await addTyped(user, 'Películas', '/media');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      expect(within(dialog).getByText('Películas · /media/MKV → /media')).toBeInTheDocument();
      expect(
        within(dialog).getByText('Sus títulos siguen dentro de la nueva carpeta y conservan su historial de reproducción.'),
      ).toBeInTheDocument();
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }));
      await within(toastRegion()).findByText('Carpetas guardadas. Escaneo iniciado');
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')[0]!.body).toMatchObject({ confirm_relinks: false });
    });

    it('PUT answered 409 relinks_unconfirmed: a fresh plan, the confirmation reopens with its relinks, no second PUT on its own', async () => {
      servePlans(
        f.librariesPlan(),
        f.librariesPlan({
          moved: [{ from_library: 'films', from_path: '/media/Anime', to_library: 'shows', to_path: '/media/Anime', title_count: 3 }],
        }),
      );
      let puts = 0;
      server.use(
        http.put(`${API}/admin/libraries`, () =>
          ++puts === 1
            ? apiError(409, 'relinks_unconfirmed', { details: { moves: [], relayouts: [] } })
            : HttpResponse.json({ ...f.librariesResponse(), scan: null }),
        ),
      );
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Series', '/media/Anime');
      await user.click(saveButton());
      const dialog = await confirmDialog('¿Guardar estos cambios de carpetas?');
      expect(within(dialog).getByText('Películas → Series · /media/Anime (3 títulos)')).toBeInTheDocument();
      await rec.settled();
      expect(rec.find('POST', '/api/admin/libraries/plan')).toHaveLength(2);
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(1);
      expect(rec.find('PUT', '/api/admin/libraries')[0]!.body).toMatchObject({ confirm_relinks: false });
      expect(within(row('/media/Anime', 'Series')).getByText('Nueva')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Guardar cambios' }));
      await within(toastRegion()).findByText('Carpetas guardadas');
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(2);
      expect(rec.find('PUT', '/api/admin/libraries')[1]!.body).toMatchObject({ confirm_relinks: true });
    });

    it('relinks_unconfirmed then an empty fresh plan still waits for the admin', async () => {
      server.use(http.put(`${API}/admin/libraries`, () => apiError(409, 'relinks_unconfirmed')));
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Series', '/media/Anime');
      await user.click(saveButton());
      await confirmDialog('¿Guardar estos cambios de carpetas?');
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(1);
    });

    it('plan answered 422: row errors and the summary, no dialog, no PUT; errors clear on the next change', async () => {
      servePlans(
        apiError(422, 'invalid_library_folders', {
          details: { folders: [{ library: 'films', path: '/media/Docs', code: 'not_found', other: null }] },
        }),
      );
      const rec = recordRequests();
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await user.click(saveButton());
      expect(await within(row('/media/Docs')).findByRole('alert')).toHaveTextContent('La carpeta no existe en el servidor.');
      expect(screen.getByText('Revisa las carpetas marcadas.')).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);
      await user.click(within(row('/media/TV', 'Series')).getByRole('button', { name: 'Quitar /media/TV' }));
      expect(screen.queryByText('Revisa las carpetas marcadas.')).not.toBeInTheDocument();
      expect(within(row('/media/Docs')).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('plan answered 422 validation_error: the catalog text as a toast, no row errors', async () => {
      servePlans(apiError(422, 'validation_error', { details: { errors: [] } }));
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await user.click(saveButton());
      expect(await within(toastRegion()).findByText('Revisa los datos introducidos.')).toBeInTheDocument();
      expect(screen.queryByText('Revisa las carpetas marcadas.')).not.toBeInTheDocument();
    });

    it('PUT answered 422: each Spanish message under its row, unmatched errors above the save bar, the draft kept', async () => {
      server.use(
        http.put(`${API}/admin/libraries`, () =>
          apiError(422, 'invalid_library_folders', {
            details: {
              folders: [
                { library: 'films', path: '/media/Docs', code: 'not_found', other: null },
                { library: 'shows', path: '/media/Films/Series', code: 'overlaps_folder', other: '/media/Films' },
                { library: 'shows', path: '/media/Otra', code: 'inside_data_dir', other: null },
              ],
            },
          }),
        ),
      );
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await addTyped(user, 'Series', '/media/Films/Series');
      await user.click(saveButton());
      expect(await within(row('/media/Docs')).findByRole('alert')).toHaveTextContent('La carpeta no existe en el servidor.');
      expect(within(row('/media/Films/Series', 'Series')).getByRole('alert')).toHaveTextContent(
        'Se solapa con /media/Films: una carpeta no puede estar dentro de otra.',
      );
      expect(screen.getByText('Revisa las carpetas marcadas.')).toBeInTheDocument();
      const unmatched = screen.getByRole('list', { name: 'Revisa las carpetas marcadas.' });
      expect(unmatched).toHaveTextContent('Series · /media/Otra');
      expect(unmatched).toHaveTextContent('Esta carpeta está dentro de los datos de HomeTV.');
      expect(within(row('/media/Docs')).getByText('Nueva')).toBeInTheDocument();
      expect(saveButton()).toBeEnabled();
    });

    it('PUT answered 409 scan_in_progress: the alert, the scan status seeded, the draft kept', async () => {
      const running = f.scanStatus({ state: 'running', trigger: 'scheduled', finished_at: null });
      server.use(http.put(`${API}/admin/libraries`, () => apiError(409, 'scan_in_progress', { details: { scan: running } })));
      const { user, queryClient } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await user.click(saveButton());
      expect(await screen.findByRole('alert')).toHaveTextContent('Hay un escaneo en curso; guarda cuando termine.');
      expect(queryClient.getQueryData(queryKeys.admin.scan())).toMatchObject({ state: 'running', trigger: 'scheduled' });
      expect(within(row('/media/Docs')).getByText('Nueva')).toBeInTheDocument();
      expect(screen.getAllByText('Hay un escaneo en curso; guarda cuando termine.')).toHaveLength(1);
    });

    it('while a scan runs, "Guardar cambios" is disabled with the hint', async () => {
      server.use(http.get(`${API}/admin/scan`, () => HttpResponse.json(f.scanStatus({ state: 'running', finished_at: null }))));
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      expect(await screen.findByText('Hay un escaneo en curso; guarda cuando termine.')).toBeInTheDocument();
      expect(saveButton()).toBeDisabled();
      expect(discardButton()).toBeEnabled();
    });

    it('any other error on the PUT is a toast', async () => {
      server.use(http.put(`${API}/admin/libraries`, () => apiError(500, 'internal')));
      const { user } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await user.click(saveButton());
      expect(await within(toastRegion()).findByText('Se ha producido un error inesperado (internal).')).toBeInTheDocument();
      expect(within(row('/media/Docs')).getByText('Nueva')).toBeInTheDocument();
    });

    it('admin token dropped after load and the keypad cancelled: Home, no PUT; returning shows the server lists', async () => {
      const rec = recordRequests();
      const { user, router } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      adminUnlock.clear();
      const keypad = await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await user.click(within(keypad).getByRole('button', { name: 'Cancelar' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/'));
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);
      expect(rec.find('POST', '/api/admin/libraries/plan')).toHaveLength(0);

      await router.navigate('/admin/libraries');
      await user.keyboard('1234');
      await loaded();
      expect(within(section('Películas')).queryByRole('listitem', { name: '/media/Docs' })).not.toBeInTheDocument();
    });

    it('plan answered 401 admin_locked and the keypad cancelled: Home, no PUT', async () => {
      servePlans(apiError(401, 'admin_locked'));
      const rec = recordRequests();
      const { user, router } = renderLibraries();
      await loaded();
      await addTyped(user, 'Películas', '/media/Docs');
      await user.click(saveButton());
      const keypad = await screen.findByRole('dialog', { name: 'Introduce el PIN de administrador' });
      await user.click(within(keypad).getByRole('button', { name: 'Cancelar' }));
      await waitFor(() => expect(currentUrl(router)).toBe('/'));
      await rec.settled();
      expect(rec.find('PUT', '/api/admin/libraries')).toHaveLength(0);
    });
  });
});
