import { useEffect, useState } from 'react';
import { Spinner } from '@/components/Spinner';
import { formatDuration } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Player.module.css';

/** §9 step 4a: "… quedan unos N min" (N = ceil(estimate / 60)), "… queda menos de un minuto" below 60 s. */
export function preparingText(estimateSeconds: number | null, audioSwitch: boolean): string {
  if (audioSwitch) return S.player.preparing.audio;
  if (estimateSeconds == null) return S.player.preparing.plain;
  if (estimateSeconds < 60) return S.player.preparing.underMinute;
  return S.player.preparing.minutes(Math.ceil(estimateSeconds / 60));
}

export interface PreparingOverlayProps {
  /** The latest `details.estimated_seconds`. */
  estimateSeconds: number | null;
  /** `prepareStartedAt` (ms). */
  startedAt: number | null;
  /** The wait belongs to an audio switch. */
  audioSwitch: boolean;
  onCancel: () => void;
}

export function PreparingOverlay({ estimateSeconds, startedAt, audioSwitch, onCancel }: PreparingOverlayProps) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const elapsed = startedAt == null ? 0 : Math.max(0, Math.floor((now - startedAt) / 1000));
  return (
    <div className={styles.screen}>
      <div className={styles.screenBox} role="status">
        <p className={styles.screenHeading}>
          <Spinner label={S.player.preparing.plain} /> {preparingText(estimateSeconds, audioSwitch)}
        </p>
        <p className={styles.screenText}>
          {S.player.preparing.detail}{' '}
          <span className={styles.elapsed} aria-label={S.player.preparing.elapsed}>
            {formatDuration(elapsed)}
          </span>
        </p>
        <div className={styles.screenActions}>
          <button type="button" className="button" onClick={onCancel}>
            {S.common.cancel}
          </button>
        </div>
      </div>
    </div>
  );
}
