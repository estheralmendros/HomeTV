/** Page reload behind a seam so tests can stub it (jsdom's location.reload is not spyable). */
export const reloader = {
  /** Reloads the current page, or loads `href` (a new history entry) when it is another URL. */
  reload(href?: string): void {
    const { pathname, search, hash } = window.location;
    if (href !== undefined && href !== pathname + search + hash) window.location.assign(href);
    else window.location.reload();
  },
};
