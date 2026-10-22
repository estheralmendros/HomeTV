/** Page reload behind a seam so tests can stub it (jsdom's location.reload is not spyable). */
export const reloader = {
  reload(): void {
    window.location.reload();
  },
};
