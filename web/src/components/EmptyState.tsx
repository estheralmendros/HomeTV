import type { ReactNode } from 'react';
import styles from './State.module.css';

export function EmptyState({ message, children }: { message: string; children?: ReactNode }) {
  return (
    <div className={styles.state}>
      <p className={styles.message}>{message}</p>
      {children && <div className={styles.actions}>{children}</div>}
    </div>
  );
}
