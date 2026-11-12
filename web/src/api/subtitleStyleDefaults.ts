// The server's default subtitle style (`hometv/subtitle_style.py` DEFAULT_STYLE). A plain constant
// with no player code, so the eager AuthProvider can seed ['me'] without pulling a player module
// into the initial chunk; `player/subtitleStyle.ts` re-exports it.
import type { SubtitleStyle } from './types';

export const DEFAULT_SUBTITLE_STYLE: SubtitleStyle = Object.freeze({
  size: 'medium',
  color: 'white',
  edge: 'outline',
  edge_color: 'black',
  position: 'low',
});
