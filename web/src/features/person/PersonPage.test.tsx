import { screen, waitFor, within } from '@testing-library/react';
import { onlineManager } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '@/api/queryKeys';
import type { PersonDetail } from '@/api/types';
import { useOverflows } from '@/lib/useOverflows';
import * as f from '../../../tests/fixtures';
import { API, apiError } from '../../../tests/msw/handlers';
import { recordRequests, server } from '../../../tests/msw/server';
import {
  createTestQueryClient,
  currentUrl,
  findProbedLocation,
  renderApp,
  renderWithProviders,
} from '../../../tests/renderWithProviders';
import { setViewportWidth } from '../../../tests/viewport';
import { PersonPage } from './PersonPage';

vi.mock('@/lib/useOverflows', () => ({ useOverflows: vi.fn(() => true) }));

function servePerson(overrides: Partial<PersonDetail> = {}) {
  const person = f.personDetail(overrides);
  server.use(http.get(`${API}/people/:id`, () => HttpResponse.json(person)));
  return person;
}

function renderPerson(route = '/people/3') {
  return renderWithProviders(<PersonPage />, { path: '/people/:id', route });
}

const DEGRADED: Partial<PersonDetail> = {
  biography: null,
  biography_language: null,
  birthday: null,
  deathday: null,
  place_of_birth: null,
  credits_complete: false,
};

beforeEach(() => {
  vi.mocked(useOverflows).mockReturnValue(true);
});

/** Freezes Date (only, no fake timers) for the tests whose text depends on "today". */
function freezeToday() {
  vi.setSystemTime(new Date(2026, 9, 27, 12, 0));
}

afterEach(() => {
  vi.useRealTimers();
});

describe('PersonPage', () => {
  it('shows the photo, name, life span, place of birth, biography and the document title', async () => {
    freezeToday();
    const { user } = renderPerson();
    const heading = await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' });
    const header = heading.closest('header')!;
    expect(within(header).getByText('27 de diciembre de 1995 (30 años)')).toBeInTheDocument();
    expect(within(header).getByText('Manhattan, New York City, New York, USA')).toBeInTheDocument();
    const img = header.querySelector('img')!;
    expect(img).toHaveAttribute('src', '/api/artwork/w185/chalamet.jpg?token=token-1');
    expect(img).toHaveAttribute('srcset', '/api/artwork/h632/chalamet.jpg?token=token-1 2x');
    expect(img).toHaveAttribute('loading', 'eager');
    expect(img.parentElement).toHaveAttribute('data-kind', 'poster');

    const bio = screen.getByRole('region', { name: 'Biografía' });
    expect(within(bio).getByText('Timothée Hal Chalamet es un actor estadounidense.')).toHaveAttribute('lang', 'es');
    await user.click(within(bio).getByRole('button', { name: 'Leer más' }));
    expect(within(bio).getByRole('button', { name: 'Leer menos' })).toHaveAttribute('aria-expanded', 'true');
    expect(document.title).toBe('Timothée Chalamet · HomeTV');
  });

  it('an English fallback biography gets lang="en"', async () => {
    servePerson({ biography: 'Timothée Hal Chalamet is an American actor.', biography_language: 'en-US' });
    renderPerson();
    expect(await screen.findByText('Timothée Hal Chalamet is an American actor.')).toHaveAttribute('lang', 'en');
  });

  it('a biography without a language has no lang attribute', async () => {
    servePerson({ biography_language: null });
    renderPerson();
    expect(await screen.findByText('Timothée Hal Chalamet es un actor estadounidense.')).not.toHaveAttribute('lang');
  });

  it('a biography that fits has no toggle', async () => {
    vi.mocked(useOverflows).mockReturnValue(false);
    renderPerson();
    await screen.findByRole('region', { name: 'Biografía' });
    expect(screen.queryByRole('button', { name: 'Leer más' })).not.toBeInTheDocument();
  });

  it('a deceased person shows both dates and the age at death', async () => {
    freezeToday();
    servePerson({ name: 'Ann Old', birthday: '1932-10-08', deathday: '2010-05-03', place_of_birth: null });
    renderPerson();
    expect(await screen.findByText('8 de octubre de 1932 – 3 de mayo de 2010 (77 años)')).toBeInTheDocument();
  });

  it('lists the appearances in server order with subtitles, badges and links', async () => {
    servePerson({
      appearances: [
        f.filmAppearance({ item: f.filmSummary({ id: 31, title: 'Wonka', year: 2023 }), character: 'Willy Wonka' }),
        f.filmAppearance({
          item: f.filmSummary({ progress: f.progress({ watched: true }) }),
          character: 'Paul Atreides',
        }),
        f.showAppearance({
          item: f.showSummary({ id: 9, title: 'Homeland', year: 2011, unwatched_episode_count: 90 }),
          character: 'Finn Walden',
        }),
        f.filmAppearance({ item: f.filmSummary({ id: 40, title: 'Interstellar', year: 2014 }), character: null }),
      ],
    });
    const { user } = renderPerson();
    const section = await screen.findByRole('region', { name: 'En tu catálogo' });
    const links = within(section).getAllByRole('link');
    expect(links.map((l) => l.getAttribute('aria-label'))).toEqual([
      'Wonka (2023), como Willy Wonka',
      'Dune (2021), como Paul Atreides',
      'Homeland (2011), como Finn Walden',
      'Interstellar (2014)',
    ]);
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/films/31', '/films/12', '/shows/9', '/films/40']);
    expect(within(links[1]!).getByText('✓')).toBeInTheDocument();
    expect(within(links[2]!).getByText('90 sin ver')).toBeInTheDocument();
    expect(within(links[2]!).getByText('como Finn Walden')).toBeInTheDocument();
    expect(screen.queryByText('No aparece en ningún título disponible de tu catálogo.')).not.toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();

    await user.click(links[1]!);
    expect(await findProbedLocation()).toBe('/films/12');
  });

  it('degraded: no biography section, no life span or place, the incomplete note below the list', async () => {
    servePerson(DEGRADED);
    const { container } = renderPerson();
    const heading = await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' });
    const header = heading.closest('header')!;
    expect(header.querySelectorAll('p')).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Biografía' })).not.toBeInTheDocument();
    const section = screen.getByRole('region', { name: 'En tu catálogo' });
    expect(within(section).getByRole('link', { name: 'Dune (2021), como Paul Atreides' })).toBeInTheDocument();
    const note = within(section).getByRole('note');
    expect(note).toHaveTextContent('Puede que falten títulos: no se ha podido consultar TMDB.');
    expect(section.lastElementChild).toBe(note);
    expect(container.querySelectorAll('section')).toHaveLength(1);
  });

  it('no photo: the initial in the photo box', async () => {
    servePerson({ profile_image: null });
    renderPerson();
    const header = (await screen.findByRole('heading', { level: 1 })).closest('header')!;
    expect(header.querySelector('img')).toBeNull();
    expect(within(header).getByText('T')).toHaveAttribute('aria-hidden', 'true');
  });

  it('an empty list shows the empty text', async () => {
    servePerson({ appearances: [] });
    renderPerson();
    const section = await screen.findByRole('region', { name: 'En tu catálogo' });
    expect(within(section).getByText('No aparece en ningún título disponible de tu catálogo.')).toBeInTheDocument();
    expect(within(section).queryByRole('link')).not.toBeInTheDocument();
    expect(within(section).queryByRole('note')).not.toBeInTheDocument();
  });

  it('empty and degraded: the empty text, then the incomplete note', async () => {
    servePerson({ ...DEGRADED, appearances: [] });
    renderPerson();
    const section = await screen.findByRole('region', { name: 'En tu catálogo' });
    expect(section).toHaveTextContent(
      /^En tu catálogoNo aparece en ningún título disponible de tu catálogo\.Puede que falten títulos: no se ha podido consultar TMDB\.$/,
    );
  });

  it('404: "Esta persona no está disponible." with a link home', async () => {
    server.use(http.get(`${API}/people/:id`, () => apiError(404, 'not_found')));
    renderPerson('/people/999');
    expect(await screen.findByText('Esta persona no está disponible.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a Inicio' })).toHaveAttribute('href', '/');
  });

  it.each(['/people/abc', '/people/0', '/people/-1'])('%s: not available without a request', async (route) => {
    const rec = recordRequests();
    renderPerson(route);
    expect(await screen.findByText('Esta persona no está disponible.')).toBeInTheDocument();
    await rec.settled();
    expect(rec.calls.filter((c) => c.url.pathname.startsWith('/api/people'))).toEqual([]);
    rec.dispose();
  });

  it('other errors: ErrorState whose "Reintentar" refetches', async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/people/:id`, () => {
        calls += 1;
        return calls === 1 ? HttpResponse.error() : HttpResponse.json(f.personDetail());
      }),
    );
    const { user } = renderPerson();
    expect(await screen.findByText('No se puede conectar con el servidor.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' })).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  /** Renders /people/3 over a cached person older than staleTimes.person, so mounting refetches. */
  async function renderStalePerson(respond: () => Response) {
    const rec = recordRequests();
    server.use(http.get(`${API}/people/:id`, respond));
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.person(3), f.personDetail(), { updatedAt: 0 });
    renderWithProviders(<PersonPage />, { path: '/people/:id', route: '/people/3', queryClient });
    expect(screen.getByRole('heading', { level: 1, name: 'Timothée Chalamet' })).toBeInTheDocument();
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.person(3))?.status).toBe('error'));
    await rec.settled();
    expect(rec.calls.filter((c) => c.url.pathname === '/api/people/3')).toHaveLength(1);
    rec.dispose();
  }

  it('a failed background refetch keeps the cached person on screen (offline after a visit)', async () => {
    await renderStalePerson(() => HttpResponse.error());
    expect(screen.getByRole('heading', { level: 1, name: 'Timothée Chalamet' })).toBeInTheDocument();
    expect(screen.queryByText('No se puede conectar con el servidor.')).not.toBeInTheDocument();
  });

  it('a 404 on a background refetch still shows "Esta persona no está disponible."', async () => {
    await renderStalePerson(() => apiError(404, 'not_found'));
    expect(await screen.findByText('Esta persona no está disponible.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1, name: 'Timothée Chalamet' })).not.toBeInTheDocument();
  });

  it('browser offline: the connection error, not a paused skeleton; "Reintentar" loads once online', async () => {
    let online = false;
    server.use(
      http.get(`${API}/people/:id`, () => (online ? HttpResponse.json(f.personDetail()) : HttpResponse.error())),
    );
    onlineManager.setOnline(false);
    try {
      const { user } = renderPerson();
      expect(await screen.findByText('No se puede conectar con el servidor.')).toBeInTheDocument();
      online = true;
      onlineManager.setOnline(true);
      await user.click(screen.getByRole('button', { name: 'Reintentar' }));
      expect(await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' })).toBeInTheDocument();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('pending: the detail skeleton', async () => {
    renderPerson();
    expect(screen.getByRole('progressbar', { name: 'Cargando…' })).toBeInTheDocument();
    await screen.findByRole('heading', { level: 1 });
  });

  it('at 360 px: the header holds the photo and the name', async () => {
    setViewportWidth(360);
    servePerson({ name: 'Kristin Scott Thomas-Fitzgerald-Worthington', place_of_birth: 'Redruth,Cornwall,England,UnitedKingdomOfGreatBritain' });
    renderPerson();
    const heading = await screen.findByRole('heading', { level: 1, name: 'Kristin Scott Thomas-Fitzgerald-Worthington' });
    const header = heading.closest('header')!;
    expect(header.querySelector('img')).not.toBeNull();
    expect(header).toContainElement(heading);
  });
});

describe('/people/:id in the app', () => {
  it('renders under the shell: film → cast → actor → card → film, Back unwinds one page at a time', async () => {
    const { router, user } = renderApp({ route: '/films/12' });
    await screen.findByRole('heading', { level: 1, name: 'Dune' });
    await user.click(screen.getByRole('link', { name: 'Timothée Chalamet, Paul Atreides' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/people/3');
    expect(screen.getByRole('navigation')).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: 'Dune (2021), como Paul Atreides' }));
    await screen.findByRole('heading', { level: 1, name: 'Dune' });
    expect(currentUrl(router)).toBe('/films/12');

    await user.click(screen.getByRole('link', { name: 'Timothée Chalamet, Paul Atreides' }));
    await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' });

    await router.navigate(-1);
    await screen.findByRole('heading', { level: 1, name: 'Dune' });
    await router.navigate(-1);
    await waitFor(() => expect(currentUrl(router)).toBe('/people/3'));
    await screen.findByRole('heading', { level: 1, name: 'Timothée Chalamet' });
    await router.navigate(-1);
    await waitFor(() => expect(currentUrl(router)).toBe('/films/12'));
    await screen.findByRole('heading', { level: 1, name: 'Dune' });
  });
});
