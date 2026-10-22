import type { FilmSummary, ShowSummary } from '@/api/types';
import { S } from '@/strings.es';
import { PosterCard } from './PosterCard';

export interface SummaryCardProps {
  item: FilmSummary | ShowSummary;
  /** Link target (default: the detail page). */
  to?: string;
  /** Show the "Sin identificar" tag for unmatched items (admins only; the caller decides). */
  showUnmatchedTag?: boolean;
}

/**
 * Poster card of a film or show summary (grids, search, Recently Added): films show progress
 * or ✓, shows an "N sin ver" badge.
 */
export function SummaryCard({ item, to, showUnmatchedTag = false }: SummaryCardProps) {
  const tag = showUnmatchedTag && item.match_status !== 'matched' ? S.cards.unmatchedTag : null;
  if (item.type === 'film') {
    const p = item.progress;
    return (
      <PosterCard
        to={to ?? `/films/${item.id}`}
        title={item.title}
        year={item.year}
        poster={item.poster}
        watched={p?.watched === true}
        progress={p && !p.watched && p.position_seconds > 0 ? { position: p.position_seconds, duration: p.duration_seconds } : null}
        tag={tag}
      />
    );
  }
  return (
    <PosterCard
      to={to ?? `/shows/${item.id}`}
      title={item.title}
      year={item.year}
      poster={item.poster}
      badge={item.unwatched_episode_count > 0 ? S.common.unwatched(item.unwatched_episode_count) : null}
      tag={tag}
    />
  );
}
