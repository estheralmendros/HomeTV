import { describe, expect, it } from 'vitest';
import { nextPageParam } from './paging';

describe('nextPageParam', () => {
  it.each<[string, { page: number; page_size: number; total: number }, number | undefined]>([
    ['more pages left', { page: 1, page_size: 50, total: 120 }, 2],
    ['past the last page', { page: 3, page_size: 50, total: 120 }, undefined],
    ['exact boundary', { page: 2, page_size: 50, total: 100 }, undefined],
    ['empty list', { page: 1, page_size: 50, total: 0 }, undefined],
  ])('%s', (_name, meta, expected) => {
    expect(nextPageParam({ items: [], ...meta })).toBe(expected);
  });
});
