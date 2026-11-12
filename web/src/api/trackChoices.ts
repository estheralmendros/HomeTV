// Remembered audio/subtitle choice per profile and title (Bearer only; 204).
import { apiFetch } from './client';
import { kindSegment } from './kind';
import type { TitleKind, TrackChoice } from './types';

/** Replaces the whole choice of the title (a null part clears it). */
export const putTrackChoice = (kind: TitleKind, id: number, choice: TrackChoice) =>
  apiFetch<void>(`/api/track-choices/${kindSegment(kind)}/${id}`, { method: 'PUT', body: choice });
