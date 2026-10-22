// Episode display texts shared by cards, rows and the player title.
import type { EpisodeSummary } from '@/api/types';
import { S } from '@/strings.es';
import { episodeCode } from './format';

type EpisodeText = Pick<EpisodeSummary, 'title' | 'episode_number'>;

/** The title, else "Episodio N". */
export function episodeTitle(ep: EpisodeText): string {
  return ep.title?.trim() ? ep.title : S.show.episodeName(ep.episode_number);
}

/** "T2 · E3 · Office Olympics" */
export function episodeLine(ep: EpisodeText & Pick<EpisodeSummary, 'season_number'>): string {
  return `${episodeCode(ep.season_number, ep.episode_number)} · ${episodeTitle(ep)}`;
}

/** "The Office · T2 · E3 · Office Olympics" (card accessible names, player title). */
export function episodeLabel(ep: EpisodeText & Pick<EpisodeSummary, 'season_number' | 'show_title'>): string {
  return `${ep.show_title} · ${episodeLine(ep)}`;
}
