import { createMemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { isSamePageReplace, locationSubscriber, type LocationChange } from './routerLocation';

describe('locationSubscriber', () => {
  it('reports each committed change with its action and the previous pathname', async () => {
    const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: ['/films', '/films/12?season=2'] });
    const changes: LocationChange[] = [];
    const unsubscribe = locationSubscriber(router)((change) => changes.push(change));
    await router.navigate('/films/12', { replace: true });
    await router.navigate('/shows/7');
    await router.navigate(-1);
    unsubscribe();
    await router.navigate('/');
    expect(changes).toEqual([
      { pathname: '/films/12', previousPathname: '/films/12', action: 'REPLACE' },
      { pathname: '/shows/7', previousPathname: '/films/12', action: 'PUSH' },
      { pathname: '/films/12', previousPathname: '/shows/7', action: 'POP' },
    ]);
    router.dispose();
  });
});

describe('isSamePageReplace', () => {
  it.each<[LocationChange, boolean]>([
    [{ pathname: '/shows/7', previousPathname: '/shows/7', action: 'REPLACE' }, true],
    [{ pathname: '/films/13', previousPathname: '/films/12', action: 'REPLACE' }, false],
    [{ pathname: '/films/12', previousPathname: '/films/12', action: 'PUSH' }, false],
    [{ pathname: '/films/12', previousPathname: '/films/12', action: 'POP' }, false],
  ])('%j → %s', (change, expected) => {
    expect(isSamePageReplace(change)).toBe(expected);
  });
});
