import { Link } from 'react-router-dom';
import type { Image } from '@/api/types';
import { S } from '@/strings.es';
import { Artwork } from './Artwork';
import styles from './Card.module.css';
import { ProgressBar } from './ProgressBar';

export interface PosterCardProps {
  to: string;
  title: string;
  year?: number | null;
  poster: Image | null;
  /** In-progress bar (films). */
  progress?: { position: number; duration: number } | null;
  /** ✓ corner (watched films). */
  watched?: boolean;
  /** Corner badge text, e.g. "12 sin ver". */
  badge?: string | null;
  /** Small tag, e.g. "Sin identificar" (admins only; the caller decides). */
  tag?: string | null;
  /** Show the title/year lines below the poster (default true). */
  showMeta?: boolean;
  /** Line below the year, e.g. "como Paul Atreides" (actor page); also part of the link name. */
  subtitle?: string | null;
}

/** 2:3 poster link with `aria-label="Dune (2021)"` ("Dune (2021), como Paul Atreides" with a subtitle). */
export function PosterCard({
  to,
  title,
  year = null,
  poster,
  progress,
  watched,
  badge,
  tag,
  showMeta = true,
  subtitle = null,
}: PosterCardProps) {
  const label = S.common.titleWithYear(title, year);
  return (
    <Link to={to} className={styles.card} aria-label={subtitle ? S.cards.withSubtitle(label, subtitle) : label}>
      <Artwork image={poster} title={title} kind="poster">
        {tag && <span className={styles.tag}>{tag}</span>}
        {watched ? (
          <span className={styles.watched} aria-hidden="true">
            ✓
          </span>
        ) : (
          badge && <span className={styles.badge}>{badge}</span>
        )}
        {progress && !watched && (
          <ProgressBar className={styles.progress} value={progress.position} max={progress.duration} />
        )}
      </Artwork>
      {showMeta && (
        <>
          <span className={styles.title}>{title}</span>
          {year !== null && <span className={styles.meta}>{year}</span>}
          {subtitle && <span className={styles.subtitle}>{subtitle}</span>}
        </>
      )}
    </Link>
  );
}
