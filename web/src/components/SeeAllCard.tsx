import { Link } from 'react-router-dom';
import { S } from '@/strings.es';
import styles from './SeeAllCard.module.css';

export interface SeeAllCardProps {
  /** Target screen, e.g. "/watchlist", "/films?sort=added". */
  to: string;
  /** The row title; names the link "Ver todo: <rowTitle>". */
  rowTitle: string;
}

/** The last card of a Home row: a poster-sized "Ver todo" link to the full screen. */
export function SeeAllCard({ to, rowTitle }: SeeAllCardProps) {
  return (
    <Link to={to} className={styles.card} aria-label={S.home.seeAllOf(rowTitle)}>
      <span className={styles.frame}>
        <span className={styles.label}>{S.home.seeAll}</span>
      </span>
    </Link>
  );
}
