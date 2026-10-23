import { afterEach, describe, expect, it, vi } from 'vitest';
import { reloader } from './reload';

// jsdom's window.location is non-configurable with unspyable methods: swap the whole `location`
// global for a plain object for the duration of each test.
function stubLocation(url: string) {
  const parsed = new URL(url);
  const location = { pathname: parsed.pathname, search: parsed.search, hash: parsed.hash, assign: vi.fn(), reload: vi.fn() };
  vi.stubGlobal('location', location);
  return location;
}

describe('reloader.reload', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads another URL with location.assign', () => {
    const location = stubLocation('http://localhost/films/3?x=1#a');
    reloader.reload('/admin/library');
    expect(location.assign).toHaveBeenCalledWith('/admin/library');
    expect(location.reload).not.toHaveBeenCalled();
  });

  it('reloads when href is the current pathname + search + hash', () => {
    const location = stubLocation('http://localhost/films/3?x=1#a');
    reloader.reload('/films/3?x=1#a');
    expect(location.reload).toHaveBeenCalledTimes(1);
    expect(location.assign).not.toHaveBeenCalled();
  });

  it('reloads without an href', () => {
    const location = stubLocation('http://localhost/films/3');
    reloader.reload();
    expect(location.reload).toHaveBeenCalledTimes(1);
    expect(location.assign).not.toHaveBeenCalled();
  });

  it('a different query string is another URL', () => {
    const location = stubLocation('http://localhost/films/3?x=1');
    reloader.reload('/films/3?x=2');
    expect(location.assign).toHaveBeenCalledWith('/films/3?x=2');
  });
});
