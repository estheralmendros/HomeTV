import type { Progress } from '@/api/types';

/**
 * Optimistic `progress` after a watched toggle (film detail, episode rows); the server's answer
 * replaces it when the query is invalidated.
 */
export function withWatched(progress: Progress | null, watched: boolean, now: string = new Date().toISOString()): Progress | null {
  if (progress) return { ...progress, watched, watched_at: watched ? now : null };
  if (!watched) return null;
  return { position_seconds: 0, duration_seconds: 0, watched: true, watched_at: now, media_file_id: null, updated_at: now };
}
