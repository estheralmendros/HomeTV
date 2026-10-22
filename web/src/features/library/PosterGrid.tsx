import type { FilmSummary, ShowSummary } from '@/api/types';
import { SummaryCard } from '@/components/SummaryCard';
import styles from './Library.module.css';

export interface PosterGridProps {
  items: ReadonlyArray<FilmSummary | ShowSummary>;
  /** Admins see "Sin identificar" tags on unmatched items. */
  showUnmatchedTags?: boolean;
}

/** CSS grid of 2:3 posters: minmax(150px, 1fr), minmax(104px, 1fr) below 600 px (≥ 3 columns at 360 px). */
export function PosterGrid({ items, showUnmatchedTags = false }: PosterGridProps) {
  return (
    <ul className={styles.grid}>
      {items.map((item) => (
        <li key={`${item.type}-${item.id}`} className={styles.cell}>
          <SummaryCard item={item} showUnmatchedTag={showUnmatchedTags} />
        </li>
      ))}
    </ul>
  );
}
