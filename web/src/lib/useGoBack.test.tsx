import { act, fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { currentUrl, renderWithProviders } from '../../tests/renderWithProviders';
import { historyIndex } from './historyIndex';
import { useGoBack } from './useGoBack';

function Back() {
  const goBack = useGoBack('/home');
  return (
    <button type="button" onClick={goBack}>
      back
    </button>
  );
}

const render = (route: string, history?: string[]) => renderWithProviders(<Back />, { path: '/watch/:id', route, history });
const clickBack = () => fireEvent.click(screen.getByRole('button', { name: 'back' }));

describe('useGoBack', () => {
  it('deep link: replaces with the fallback', async () => {
    const { router } = render('/watch/1');
    clickBack();
    await act(async () => {});
    expect(currentUrl(router)).toBe('/home');
  });

  it('deep link + a replace (e.g. dropping ?t=): still the fallback, never navigate(-1)', async () => {
    const { router } = render('/watch/1?t=30');
    await act(() => router.navigate('/watch/1', { replace: true }));
    await act(() => router.navigate('/watch/2', { replace: true }));
    clickBack();
    await act(async () => {});
    expect(currentUrl(router)).toBe('/home');
    expect(historyIndex.read()).toBe(0);
  });

  it('with in-app history: goes back, also after a replace', async () => {
    const { router } = render('/watch/1?t=30', ['/films/3']);
    await act(() => router.navigate('/watch/1', { replace: true }));
    clickBack();
    await act(async () => {});
    expect(currentUrl(router)).toBe('/films/3');
  });

  it('after an in-app push from a deep link: goes back to the deep link', async () => {
    const { router } = render('/watch/1');
    await act(() => router.navigate('/watch/2'));
    expect(historyIndex.read()).toBe(1);
    clickBack();
    await act(async () => {});
    expect(currentUrl(router)).toBe('/watch/1');
    expect(historyIndex.read()).toBe(0);
  });
});

describe('historyIndex (browser router)', () => {
  it("reads React Router's history.state.idx, 0 without one", () => {
    const saved = window.history.state as unknown;
    try {
      window.history.replaceState({ usr: null, key: 'k', idx: 3 }, '');
      expect(historyIndex.browser()).toBe(3);
      window.history.replaceState(null, '');
      expect(historyIndex.browser()).toBe(0);
    } finally {
      window.history.replaceState(saved, '');
    }
  });
});
