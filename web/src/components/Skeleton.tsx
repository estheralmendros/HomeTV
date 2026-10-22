import { S } from '@/strings.es';
import { cx } from '@/lib/cx';
import styles from './Skeleton.module.css';

export function Skeleton({ className }: { className?: string }) {
  return <span className={cx(styles.skeleton, className)} aria-hidden="true" />;
}

export function SkeletonCard({ variant = 'poster' }: { variant?: 'poster' | 'still' }) {
  return (
    <div className={styles.card} data-testid="skeleton-card" aria-hidden="true">
      <Skeleton className={variant === 'poster' ? styles.poster : styles.still} />
      <Skeleton className={styles.line} />
    </div>
  );
}

/** A loading row of `count` card placeholders (Home: 8). */
export function SkeletonRow({ count = 8, variant = 'poster' }: { count?: number; variant?: 'poster' | 'still' }) {
  return (
    <div className={cx(styles.row, variant === 'still' && styles.rowStill)} role="progressbar" aria-label={S.common.loading}>
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} variant={variant} />
      ))}
    </div>
  );
}

/** A loading poster grid (Library: 18). */
export function SkeletonGrid({ count = 18 }: { count?: number }) {
  return (
    <div className={styles.grid} role="progressbar" aria-label={S.common.loading}>
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  );
}

/** A loading detail page (backdrop, poster and text lines). */
export function SkeletonDetail() {
  return (
    <div className={styles.detail} role="progressbar" aria-label={S.common.loading}>
      <Skeleton className={styles.detailPoster} />
      <div className={styles.detailText}>
        <Skeleton className={styles.line} />
        <Skeleton className={styles.line} />
        <Skeleton className={styles.line} />
      </div>
    </div>
  );
}
