import { Link } from 'react-router-dom';
import type { Image } from '@/api/types';
import { Artwork } from './Artwork';
import styles from './Card.module.css';
import { ProgressBar } from './ProgressBar';

export interface EpisodeCardProps {
  to: string;
  still: Image | null;
  showTitle: string;
  /** "T2 · E3 · Título" */
  line: string;
  /** Accessible name of the link, e.g. "The Office · T2 · E3 · Office Olympics". */
  ariaLabel: string;
  progress?: { position: number; duration: number } | null;
}

/** 16:9 episode card (Continue Watching). */
export function EpisodeCard({ to, still, showTitle, line, ariaLabel, progress }: EpisodeCardProps) {
  return (
    <Link to={to} className={styles.card} aria-label={ariaLabel}>
      <Artwork image={still} title={showTitle} kind="still">
        {progress && <ProgressBar className={styles.progress} value={progress.position} max={progress.duration} />}
      </Artwork>
      <span className={styles.episodeText}>
        <span className={styles.title}>{showTitle}</span>
        <span className={styles.secondary}>{line}</span>
      </span>
    </Link>
  );
}
