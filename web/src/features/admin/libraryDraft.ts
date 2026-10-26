// The Bibliotecas draft (prd2 folder-selection-admin §3): pure, table-tested. Nothing reaches the
// server until "Guardar cambios"; the consequences of a save come from the server's plan
// (POST /api/admin/libraries/plan), never from here.
import type { LibrariesPlan, LibrariesPlanRequest, LibrariesResponse, LibraryType } from '@/api/types';

export interface DraftRow {
  path: string;
  origin: 'saved' | 'added';
  removed: boolean;
}

export interface LibraryDraft {
  films: DraftRow[];
  shows: DraftRow[];
}

export type DraftAction =
  | { type: 'reset'; saved: LibrariesPlanRequest }
  | { type: 'add'; library: LibraryType; path: string }
  | { type: 'remove'; library: LibraryType; path: string }
  | { type: 'undo'; library: LibraryType; path: string };

export const LIBRARIES: readonly LibraryType[] = ['films', 'shows'];

const other = (library: LibraryType): LibraryType => (library === 'films' ? 'shows' : 'films');

/** The saved path lists of a GET/PUT response. */
export function savedLists(response: LibrariesResponse): LibrariesPlanRequest {
  return { films: response.films.map((f) => f.path), shows: response.shows.map((f) => f.path) };
}

export function initialDraft(saved: LibrariesPlanRequest): LibraryDraft {
  const rows = (paths: string[]): DraftRow[] => paths.map((path) => ({ path, origin: 'saved', removed: false }));
  return { films: rows(saved.films), shows: rows(saved.shows) };
}

export function draftReducer(state: LibraryDraft, action: DraftAction): LibraryDraft {
  if (action.type === 'reset') return initialDraft(action.saved);
  const { library, path } = action;
  const rows = state[library];
  switch (action.type) {
    case 'add': {
      const removedSaved = rows.find((r) => r.path === path && r.origin === 'saved' && r.removed);
      if (removedSaved) return draftReducer(state, { type: 'undo', library, path });
      if (rows.some((r) => r.path === path)) return state;
      return { ...state, [library]: [...rows, { path, origin: 'added', removed: false }] };
    }
    case 'remove': {
      const row = rows.find((r) => r.path === path);
      if (!row) return state;
      const next = row.origin === 'added' ? rows.filter((r) => r !== row) : rows.map((r) => (r === row ? { ...r, removed: true } : r));
      return { ...state, [library]: next };
    }
    case 'undo': {
      if (!rows.some((r) => r.path === path && r.removed)) return state;
      // Undoing a move: the copy added to the other library would duplicate the restored row.
      const otherLib = other(library);
      return {
        ...state,
        [library]: rows.map((r) => (r.path === path ? { ...r, removed: false } : r)),
        [otherLib]: state[otherLib].filter((r) => !(r.path === path && r.origin === 'added')),
      };
    }
  }
}

export function isDirty(draft: LibraryDraft): boolean {
  return LIBRARIES.some((lib) => draft[lib].some((r) => r.origin === 'added' || r.removed));
}

/** Saved-not-removed rows, then added rows, each in draft order. */
export function toRequest(draft: LibraryDraft): LibrariesPlanRequest {
  const paths = (rows: DraftRow[]) => [
    ...rows.filter((r) => r.origin === 'saved' && !r.removed).map((r) => r.path),
    ...rows.filter((r) => r.origin === 'added').map((r) => r.path),
  ];
  return { films: paths(draft.films), shows: paths(draft.shows) };
}

export function planIsEmpty(plan: LibrariesPlan): boolean {
  return plan.removed.length === 0 && plan.covered.length === 0 && !planHasRelinks(plan);
}

/** Moves between libraries and Series relayouts delete history: the PUT must confirm them. */
export function planHasRelinks(plan: LibrariesPlan): boolean {
  return plan.moved.length > 0 || plan.relayout.length > 0;
}

/**
 * Trims; an absolute path is normalized like the server's `normpath` (repeated and trailing "/",
 * "." and ".." segments), so duplicates and 422 row errors match the stored paths. "/" stays "/".
 * A relative path is returned trimmed (it is rejected as `not_absolute`).
 */
export function normalizeTyped(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed.startsWith('/')) return trimmed;
  const parts: string[] = [];
  for (const segment of trimmed.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') parts.pop();
    else parts.push(segment);
  }
  return `/${parts.join('/')}`;
}

/** Client checks before `add`: only `not_absolute` and `duplicate`; the server stays the authority. */
export function checkNewPath(draft: LibraryDraft, raw: string): { path: string } | { error: 'not_absolute' | 'duplicate' } {
  const path = normalizeTyped(raw);
  if (!path.startsWith('/')) return { error: 'not_absolute' };
  const present = LIBRARIES.some((lib) => draft[lib].some((r) => r.path === path && !r.removed));
  return present ? { error: 'duplicate' } : { path };
}
