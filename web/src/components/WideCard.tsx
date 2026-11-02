import { Link } from 'react-router-dom';
import type { Image } from '@/api/types';
import { S } from '@/strings.es';
import { Artwork } from './Artwork';
import styles from './Card.module.css';
import { ProgressBar } from './ProgressBar';
import { RatingBadge } from './RatingBadge';

export interface WideCardProps {
  to: string;
  /** An episode still or a film backdrop. */
  image: Image | null;
  kind: 'still' | 'backdrop';
  /** The show or film title. */
  title: string;
  /** "T2 · E3 · Título" for an episode, the year for a film. */
  line: string | null;
  /** Accessible name of the link, e.g. "The Office · T2 · E3 · Office Olympics" or "Dune (2021)". */
  ariaLabel: string;
  progress?: { position: number; duration: number } | null;
  /** The viewer's rating: a bottom-left "★ 4,5" badge, also in the link name. */
  myRating?: number | null;
}

/** 16:9 card (Continue Watching: episodes and films). */
export function WideCard({ to, image, kind, title, line, ariaLabel, progress, myRating = null }: WideCardProps) {
  const label = myRating !== null ? S.cards.withRating(ariaLabel, S.ratings.stars(myRating)) : ariaLabel;
  return (
    <Link to={to} className={styles.card} aria-label={label}>
      <Artwork image={image} title={title} kind={kind}>
        {myRating !== null && <RatingBadge stars={myRating} aboveProgress={Boolean(progress)} />}
        {progress && <ProgressBar className={styles.progress} value={progress.position} max={progress.duration} />}
      </Artwork>
      <span className={styles.episodeText}>
        <span className={styles.title}>{title}</span>
        {line && <span className={styles.secondary}>{line}</span>}
      </span>
    </Link>
  );
}
