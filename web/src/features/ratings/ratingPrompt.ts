// The rating prompt host's API (ratings prd2 FR-W8), apart from the host so the form can read it
// without an import cycle. Without a host (isolated component tests) every call is a no-op.
import { createContext, useContext } from 'react';
import type { RatingKind, RatingPrompt } from '@/api/types';
import type { LocationChange } from '@/lib/routerLocation';

export interface RatingPromptApi {
  /** Queue unless the same title is pending, shown or handled. */
  offer: (prompt: RatingPrompt) => void;
  /** Answered elsewhere (the player's inline form): never offer it again. */
  markHandled: (kind: RatingKind, id: number) => void;
  /** The player calls it while mounted; returns the release. */
  suppress: () => () => void;
}

const NO_HOST: RatingPromptApi = { offer: () => undefined, markHandled: () => undefined, suppress: () => () => undefined };

export const RatingPromptContext = createContext<RatingPromptApi>(NO_HOST);

export function useRatingPrompt(): RatingPromptApi {
  return useContext(RatingPromptContext);
}

/** Committed router location changes (the host sits outside RouterProvider); returns the unsubscribe. */
export type SubscribeLocation = (listener: (change: LocationChange) => void) => () => void;
