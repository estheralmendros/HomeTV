import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter, type RouteObject } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ROUTER_FUTURE, ROUTER_PROVIDER_FUTURE } from '@/router';
import { CHUNK_RELOAD_KEY, pendingNavigationHref, registerChunkReload } from './chunkReload';
import { lazyRoute } from './lazyRoute';
import { reloader } from './reload';
import { RouteErrorBoundary } from './RouteErrorBoundary';

const chunkError = () => new TypeError('Failed to fetch dynamically imported module: http://localhost/assets/PlayerPage-old.js');

function renderRoutes(children: RouteObject[], url: string) {
  const router = createMemoryRouter([{ path: '/', errorElement: <RouteErrorBoundary />, hydrateFallbackElement: <p>loading</p>, children }], {
    initialEntries: [url],
    future: ROUTER_FUTURE,
  });
  render(<RouterProvider router={router} future={ROUTER_PROVIDER_FUTURE} />);
  return router;
}

let reload: ReturnType<typeof vi.spyOn>;
let consoleError: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  reload = vi.spyOn(reloader, 'reload').mockImplementation(() => undefined);
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  reload.mockRestore();
  consoleError.mockRestore();
});

const APP_UPDATED = 'La aplicación se ha actualizado, pero no se han podido cargar sus archivos.';

describe('RouteErrorBoundary', () => {
  it('a lazy route whose import rejects with a chunk error reloads once', async () => {
    renderRoutes([{ path: 'watch', lazy: () => Promise.reject(chunkError()) }], '/watch');
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(APP_UPDATED)).not.toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('a second failure within 10 s renders the "app was updated" ErrorState; [Recargar] clears the marker', async () => {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now() - 2_000));
    renderRoutes([{ path: 'admin', lazy: () => Promise.reject(chunkError()) }], '/admin');
    expect(await screen.findByText(APP_UPDATED)).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Recargar' }));
    expect(sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBeNull();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('also after a non-prevented vite:preloadError (second failure): never a blank route', async () => {
    const unregister = registerChunkReload(window);
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now() - 2_000));
    const lazy = lazyRoute(async () => {
      const event = new Event('vite:preloadError', { cancelable: true });
      window.dispatchEvent(event);
      // Vite rethrows when the handler did not call preventDefault().
      if (!event.defaultPrevented) throw chunkError();
      return undefined as unknown as { C: () => null };
    }, (m) => m.C);
    renderRoutes([{ path: 'watch', lazy }], '/watch');
    expect(await screen.findByText(APP_UPDATED)).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
    unregister();
  });

  it('a prevented vite:preloadError (first failure) reloads and keeps waiting (no error screen)', async () => {
    const unregister = registerChunkReload(window);
    const lazy = lazyRoute(async () => {
      const event = new Event('vite:preloadError', { cancelable: true });
      window.dispatchEvent(event);
      if (!event.defaultPrevented) throw chunkError();
      return undefined as unknown as { C: () => null };
    }, (m) => m.C);
    renderRoutes([{ index: true, element: <p>home</p> }, { path: 'watch', lazy }], '/watch');
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText(APP_UPDATED)).not.toBeInTheDocument();
    expect(screen.queryByText('Algo ha ido mal.')).not.toBeInTheDocument();
    unregister();
  });

  it('a vite:preloadError during a navigation reloads the target URL, not the page being left', async () => {
    const lazy = lazyRoute(async () => {
      const event = new Event('vite:preloadError', { cancelable: true });
      window.dispatchEvent(event);
      if (!event.defaultPrevented) throw chunkError();
      return undefined as unknown as { C: () => null };
    }, (m) => m.C);
    const router = renderRoutes([{ path: 'films/2', element: <p>film</p> }, { path: 'watch/film/2', lazy }], '/films/2');
    const unregister = registerChunkReload(window, () => pendingNavigationHref(router));
    expect(await screen.findByText('film')).toBeInTheDocument();
    void router.navigate('/watch/film/2?file=3');
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(reload).toHaveBeenCalledWith('/watch/film/2?file=3');
    unregister();
  });

  it('any other error renders "Algo ha ido mal." with [Recargar] [Ir a Inicio]', async () => {
    function Boom(): never {
      throw new Error('boom');
    }
    renderRoutes([{ path: 'x', element: <Boom /> }], '/x');
    expect(await screen.findByText('Algo ha ido mal.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a Inicio' })).toHaveAttribute('href', '/');
    await userEvent.click(screen.getByRole('button', { name: 'Recargar' }));
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
