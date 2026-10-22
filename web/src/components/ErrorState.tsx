import type { ReactNode } from 'react';
import { S } from '@/strings.es';
import styles from './State.module.css';

export interface ErrorStateProps {
  message: string;
  /** Renders [Reintentar]. */
  onRetry?: () => void;
  /** Extra actions after Retry. */
  children?: ReactNode;
}

/** Error message (from errorMessage / the catalog) with optional [Reintentar]. Never blank (G5). */
export function ErrorState({ message, onRetry, children }: ErrorStateProps) {
  return (
    <div className={styles.state} role="alert">
      <p className={styles.message}>
        <span className={styles.icon} aria-hidden="true">
          ⚠
        </span>
        {message}
      </p>
      {(onRetry || children) && (
        <div className={styles.actions}>
          {onRetry && (
            <button type="button" className="button button-primary" onClick={onRetry}>
              {S.common.retry}
            </button>
          )}
          {children}
        </div>
      )}
    </div>
  );
}
