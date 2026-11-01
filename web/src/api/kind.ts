// The URL segment of a title kind: /api/ratings/{films|shows}, /api/watchlist/{films|shows} and
// the library query keys ([films|shows, 'list']).
import type { LibraryType, TitleKind } from './types';

export const kindSegment = (kind: TitleKind): LibraryType => (kind === 'film' ? 'films' : 'shows');
