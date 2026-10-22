import { S } from '@/strings.es';
import { cx } from '@/lib/cx';
import styles from './ProgressBar.module.css';

/** Percentage 0–100 of value/max (0 when max is not positive). */
export function progressPercent(value: number, max: number): number {
  if (!(max > 0) || !Number.isFinite(value)) return 0;
  return Math.round(Math.min(1, Math.max(0, value / max)) * 100);
}

export function ProgressBar({ value, max, label, className }: { value: number; max: number; label?: string; className?: string }) {
  const percent = progressPercent(value, max);
  return (
    <span
      className={cx(styles.track, className)}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      aria-label={label ?? S.common.progressLabel(percent)}
    >
      <span className={styles.fill} style={{ width: `${percent}%` }} />
    </span>
  );
}
