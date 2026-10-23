/**
 * Index of the current entry in this tab's session history, as React Router counts it
 * (createBrowserRouter stores it in `history.state.idx`; 0 = the first entry of this app
 * document, so > 0 means navigate(-1) stays in the app). Pushes increment it, replaces keep it.
 * `read` is a seam: the memory router used in tests does not touch window.history, so the test
 * harness swaps in its own counter (tests/renderWithProviders.tsx).
 */
export const historyIndex = {
  browser(): number {
    const state = window.history.state as { idx?: unknown } | null;
    return typeof state?.idx === 'number' ? state.idx : 0;
  },
  read(): number {
    return historyIndex.browser();
  },
};
