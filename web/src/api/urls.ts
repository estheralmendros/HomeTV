import { getToken } from './client';
import type { Image } from './types';

/**
 * Appends `token=<token>` for URLs the browser loads by itself (<video src>, <img>): only
 * `/api/media-files/{id}/stream` and `/api/artwork/*` (prd.md §8). Never for HLS or subtitles.
 */
export function withToken(url: string, token: string | null = getToken()): string {
  if (!token) return url;
  return `${url}${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`;
}

export function artworkUrl(image: Image | null | undefined, size: 'small' | 'large'): string | null {
  return image ? withToken(image[size]) : null;
}
