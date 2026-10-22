import { useAuth } from '@/auth/AuthProvider';
import { reloader } from '@/lib/reload';
import { S } from '@/strings.es';
import styles from './CrossTabBanner.module.css';

/** Non-blocking notice after another tab chose a different profile (prd2 §2). */
export function CrossTabBanner() {
  const { crossTabNotice, dismissCrossTabNotice } = useAuth();
  if (!crossTabNotice) return null;
  return (
    <div className={styles.banner} role="status">
      <p className={styles.text}>{S.crossTab.banner(crossTabNotice.name, crossTabNotice.current)}</p>
      <div className={styles.actions}>
        <button type="button" className="button" onClick={() => reloader.reload()}>
          {S.crossTab.reload}
        </button>
        <button type="button" className="button" aria-label={S.crossTab.dismiss} onClick={dismissCrossTabNotice}>
          ✕
        </button>
      </div>
    </div>
  );
}
