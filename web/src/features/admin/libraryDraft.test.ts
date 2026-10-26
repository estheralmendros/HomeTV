import { describe, expect, it } from 'vitest';
import type { LibrariesPlan } from '@/api/types';
import {
  checkNewPath,
  draftReducer,
  initialDraft,
  isDirty,
  normalizeTyped,
  planHasRelinks,
  planIsEmpty,
  savedLists,
  toRequest,
  type LibraryDraft,
} from './libraryDraft';
import * as f from '../../../tests/fixtures';

const SAVED = { films: ['/media/Films', '/media/Films4K'], shows: ['/media/TV'] };
const base = () => initialDraft(SAVED);

const emptyPlan = (): LibrariesPlan => ({ removed: [], covered: [], moved: [], relayout: [] });

describe('libraryDraft', () => {
  it('initialDraft: saved rows in server order, nothing removed, clean', () => {
    const draft = base();
    expect(draft).toEqual({
      films: [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
      ],
      shows: [{ path: '/media/TV', origin: 'saved', removed: false }],
    });
    expect(isDirty(draft)).toBe(false);
  });

  it('savedLists: the path lists of a LibrariesResponse', () => {
    const response = f.librariesResponse({ films: [f.libraryFolder({ path: '/a' })], shows: [] });
    expect(savedLists(response)).toEqual({ films: ['/a'], shows: [] });
  });

  it.each<[string, (d: LibraryDraft) => LibraryDraft, LibraryDraft['films'], LibraryDraft['shows'], boolean]>([
    [
      'add appends an added row',
      (d) => draftReducer(d, { type: 'add', library: 'films', path: '/media/Docs' }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
        { path: '/media/Docs', origin: 'added', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      true,
    ],
    [
      'remove of an added row drops it',
      (d) =>
        draftReducer(draftReducer(d, { type: 'add', library: 'films', path: '/media/Docs' }), {
          type: 'remove',
          library: 'films',
          path: '/media/Docs',
        }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      false,
    ],
    [
      'remove of a saved row marks it removed',
      (d) => draftReducer(d, { type: 'remove', library: 'films', path: '/media/Films4K' }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: true },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      true,
    ],
    [
      'undo clears removed',
      (d) =>
        draftReducer(draftReducer(d, { type: 'remove', library: 'films', path: '/media/Films4K' }), {
          type: 'undo',
          library: 'films',
          path: '/media/Films4K',
        }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      false,
    ],
    [
      're-adding a removed saved row of the same library is an undo',
      (d) =>
        draftReducer(draftReducer(d, { type: 'remove', library: 'shows', path: '/media/TV' }), {
          type: 'add',
          library: 'shows',
          path: '/media/TV',
        }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      false,
    ],
    [
      'a path removed from one library may be added to the other (a move)',
      (d) =>
        draftReducer(draftReducer(d, { type: 'remove', library: 'shows', path: '/media/TV' }), {
          type: 'add',
          library: 'films',
          path: '/media/TV',
        }),
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
        { path: '/media/TV', origin: 'added', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: true }],
      true,
    ],
    [
      'undo of a moved row drops its added copy in the other library (no duplicate)',
      (d) => {
        const moved = draftReducer(draftReducer(d, { type: 'remove', library: 'shows', path: '/media/TV' }), {
          type: 'add',
          library: 'films',
          path: '/media/TV',
        });
        return draftReducer(moved, { type: 'undo', library: 'shows', path: '/media/TV' });
      },
      [
        { path: '/media/Films', origin: 'saved', removed: false },
        { path: '/media/Films4K', origin: 'saved', removed: false },
      ],
      [{ path: '/media/TV', origin: 'saved', removed: false }],
      false,
    ],
    [
      'reset replaces the draft with the saved lists',
      (d) =>
        draftReducer(draftReducer(d, { type: 'add', library: 'films', path: '/media/Docs' }), {
          type: 'reset',
          saved: { films: [], shows: ['/media/Series'] },
        }),
      [],
      [{ path: '/media/Series', origin: 'saved', removed: false }],
      false,
    ],
  ])('%s', (_name, act, films, shows, dirty) => {
    const draft = act(base());
    expect(draft.films).toEqual(films);
    expect(draft.shows).toEqual(shows);
    expect(isDirty(draft)).toBe(dirty);
  });

  it('toRequest: saved-not-removed then added, in order', () => {
    let draft = base();
    draft = draftReducer(draft, { type: 'add', library: 'films', path: '/media/Docs' });
    draft = draftReducer(draft, { type: 'remove', library: 'films', path: '/media/Films' });
    draft = draftReducer(draft, { type: 'add', library: 'shows', path: '/media/Anime' });
    expect(toRequest(draft)).toEqual({ films: ['/media/Films4K', '/media/Docs'], shows: ['/media/TV', '/media/Anime'] });
  });

  it.each([
    ['/media/x//', '/media/x'],
    ['/', '/'],
    ['  /media/Films  ', '/media/Films'],
    ['/media//Films/./A', '/media/Films/A'],
    ['/media/Films/../TV', '/media/TV'],
    ['media/x', 'media/x'],
    ['', ''],
  ])('normalizeTyped(%j) = %j', (raw, expected) => {
    expect(normalizeTyped(raw)).toBe(expected);
  });

  it('checkNewPath: not absolute, duplicate in either library, removed rows free, ok', () => {
    let draft = base();
    expect(checkNewPath(draft, 'media/x')).toEqual({ error: 'not_absolute' });
    expect(checkNewPath(draft, '/media/Films/')).toEqual({ error: 'duplicate' });
    expect(checkNewPath(draft, '/media/TV')).toEqual({ error: 'duplicate' });
    draft = draftReducer(draft, { type: 'add', library: 'films', path: '/media/Docs' });
    expect(checkNewPath(draft, ' /media/Docs ')).toEqual({ error: 'duplicate' });
    draft = draftReducer(draft, { type: 'remove', library: 'shows', path: '/media/TV' });
    expect(checkNewPath(draft, '/media/TV')).toEqual({ path: '/media/TV' });
    expect(checkNewPath(draft, ' /media/New/ ')).toEqual({ path: '/media/New' });
  });

  it('planIsEmpty is true only when all four groups are empty', () => {
    expect(planIsEmpty(emptyPlan())).toBe(true);
    expect(planIsEmpty({ ...emptyPlan(), removed: [{ library: 'films', path: '/a', title_count: 1 }] })).toBe(false);
    expect(planIsEmpty({ ...emptyPlan(), covered: [{ library: 'films', path: '/a/b', by: '/a', title_count: 1 }] })).toBe(false);
    expect(
      planIsEmpty({
        ...emptyPlan(),
        moved: [{ from_library: 'films', from_path: '/a', to_library: 'shows', to_path: '/a', title_count: 1 }],
      }),
    ).toBe(false);
    expect(planIsEmpty({ ...emptyPlan(), relayout: [{ from_path: '/a', to_path: '/a/b', title_count: 1 }] })).toBe(false);
  });

  it('planHasRelinks is true when moved or relayout is non-empty, false for removed/covered only', () => {
    expect(planHasRelinks(emptyPlan())).toBe(false);
    expect(
      planHasRelinks({
        ...emptyPlan(),
        removed: [{ library: 'films', path: '/a', title_count: 1 }],
        covered: [{ library: 'films', path: '/a/b', by: '/a', title_count: 1 }],
      }),
    ).toBe(false);
    expect(
      planHasRelinks({
        ...emptyPlan(),
        moved: [{ from_library: 'films', from_path: '/a', to_library: 'shows', to_path: '/a', title_count: 1 }],
      }),
    ).toBe(true);
    expect(planHasRelinks({ ...emptyPlan(), relayout: [{ from_path: '/a', to_path: '/a/b', title_count: 1 }] })).toBe(true);
  });
});
