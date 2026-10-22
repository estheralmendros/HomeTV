import { S } from '@/strings.es';
import styles from './Spinner.module.css';

export function Spinner({ label = S.common.loading }: { label?: string }) {
  return <span className={styles.spinner} role="progressbar" aria-label={label} />;
}

/** Full-screen loading state (boot, guards). */
export function FullScreenSpinner({ label = S.common.loading }: { label?: string }) {
  return (
    <div className={styles.fullScreen}>
      <Spinner label={label} />
    </div>
  );
}
