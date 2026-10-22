import { describe, expect, it } from 'vitest';
import * as f from '../../tests/fixtures';
import { withWatched } from './watchedPatch';

const NOW = '2026-10-22T10:00:00Z';

describe('withWatched (optimistic progress patch)', () => {
  it('flips watched on existing progress and stamps watched_at', () => {
    const p = f.progress({ position_seconds: 100 });
    expect(withWatched(p, true, NOW)).toEqual({ ...p, watched: true, watched_at: NOW });
    expect(withWatched({ ...p, watched: true, watched_at: NOW }, false, NOW)).toEqual({ ...p, watched: false, watched_at: null });
  });

  it('creates a watched progress when there is none, and leaves none when unmarking', () => {
    expect(withWatched(null, true, NOW)).toEqual({
      position_seconds: 0,
      duration_seconds: 0,
      watched: true,
      watched_at: NOW,
      media_file_id: null,
      updated_at: NOW,
    });
    expect(withWatched(null, false, NOW)).toBeNull();
  });
});
