// window.matchMedia stub (jsdom has none). Evaluates `(max-width: Npx)` / `(min-width: Npx)`
// queries, optionally joined with `and`, against a settable viewport width. setup.ts resets the
// width to desktop (1280 px) before each test; setViewportWidth() notifies change listeners.

const DESKTOP_WIDTH = 1280;

let width = DESKTOP_WIDTH;
const lists = new Set<FakeMediaQueryList>();

function evaluate(query: string): boolean {
  return query.split(/\s+and\s+/).every((part) => {
    const m = /^\(\s*(max|min)-width:\s*(\d+)px\s*\)$/.exec(part.trim());
    if (!m) throw new Error(`matchMedia stub: unsupported query "${query}"`);
    return m[1] === 'max' ? width <= Number(m[2]) : width >= Number(m[2]);
  });
}

class FakeMediaQueryList extends EventTarget {
  matches: boolean;
  onchange: ((this: MediaQueryList, ev: MediaQueryListEvent) => unknown) | null = null;

  constructor(readonly media: string) {
    super();
    this.matches = evaluate(media);
  }

  /** Legacy Safari API, kept for completeness. */
  addListener(listener: (ev: MediaQueryListEvent) => void) {
    this.addEventListener('change', listener as EventListener);
  }

  removeListener(listener: (ev: MediaQueryListEvent) => void) {
    this.removeEventListener('change', listener as EventListener);
  }

  refresh() {
    const matches = evaluate(this.media);
    if (matches === this.matches) return;
    this.matches = matches;
    const event = Object.assign(new Event('change'), { matches, media: this.media });
    this.dispatchEvent(event);
  }
}

export function installMatchMedia() {
  width = DESKTOP_WIDTH;
  lists.clear();
  window.matchMedia = (query: string) => {
    const list = new FakeMediaQueryList(query);
    lists.add(list);
    return list as unknown as MediaQueryList;
  };
}

/** Changes the viewport width seen by matchMedia and fires `change` on affected lists. */
export function setViewportWidth(px: number) {
  width = px;
  for (const list of lists) list.refresh();
}
