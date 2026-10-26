// /admin/scan (prd2 §11 Scan): the status card of the current/last run and "Escanear ahora".
// AdminLayout polls `['admin','scan']` every 2 s while running and handles the finish.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId } from 'react';
import { startScan } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { infoQuery } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import type { ScanCounts, ScanStatus } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { ErrorState } from '@/components/ErrorState';
import { WARNING_GLYPH } from '@/components/glyphs';
import { ProgressBar } from '@/components/ProgressBar';
import { Skeleton } from '@/components/Skeleton';
import { useToast } from '@/components/Toast';
import { formatCount, formatRelative } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Admin.module.css';
import { useScanQuery } from './adminQueries';
import { isScanStatus } from './scanSummary';

const T = S.admin.scan;
const MAX_ERROR_LINES = 50;
const COUNT_ORDER: Array<keyof ScanCounts> = [
  'files_seen', 'new', 'changed', 'unchanged', 'ignored', 'unmatched', 'missing', 'restored', 'removed',
];

export function ScanPage() {
  const scan = useScanQuery();
  const { data: info } = useQuery(infoQuery());
  const queryClient = useQueryClient();
  const runAdmin = useAdminAction();
  const toast = useToast();

  const onScanNow = async () => {
    try {
      const started = await runAdmin(() => startScan(), T.scanNow);
      queryClient.setQueryData(queryKeys.admin.scan(), started);
    } catch (err) {
      if (isAdminActionCancelled(err)) return;
      if (isApiError(err, 'scan_in_progress')) {
        const current = err.details.scan;
        if (isScanStatus(current)) queryClient.setQueryData(queryKeys.admin.scan(), current);
      }
      toast.show(errorMessage(err));
    }
  };

  return (
    <div className={styles.panel}>
      {info?.tmdb_configured === false && (
        <p className={styles.warning} role="note">
          <span aria-hidden="true">{WARNING_GLYPH} </span>
          {T.tmdbNotConfigured}
        </p>
      )}
      {scan.data ? (
        <ScanCard scan={scan.data} onScanNow={() => void onScanNow()} />
      ) : scan.isError ? (
        <ErrorState message={errorMessage(scan.error)} onRetry={() => void scan.refetch()} />
      ) : (
        <div role="progressbar" aria-label={S.common.loading} className={styles.cardSkeleton}>
          <Skeleton className={styles.skeletonLine} />
          <Skeleton className={styles.skeletonLine} />
          <Skeleton className={styles.skeletonLine} />
        </div>
      )}
    </div>
  );
}

function ScanCard({ scan, onScanNow }: { scan: ScanStatus; onScanNow: () => void }) {
  const titleId = useId();
  const errorsId = useId();
  const running = scan.state === 'running';
  const stateText = scan.trigger ? T.stateWithTrigger(T.states[scan.state], T.triggers[scan.trigger]) : T.states[scan.state];
  const { processed, total } = scan.progress;
  const counts = scan.counts;
  const errorCount = scan.errors.length;

  return (
    <section className={styles.card} aria-labelledby={titleId}>
      <header className={styles.cardHeader}>
        <h2 id={titleId} className={styles.cardTitle}>
          {T.title}
        </h2>
        <span className={styles.state} data-state={scan.state}>
          <span aria-hidden="true">● </span>
          {stateText}
        </span>
        <button type="button" className="button button-primary" disabled={running} onClick={onScanNow}>
          {T.scanNow}
        </button>
      </header>

      {(scan.phase || scan.started_at) && (
        <p className={styles.meta}>
          {scan.phase && <span>{T.phase(T.phases[scan.phase])}</span>}
          {scan.started_at && <span>{T.started(formatRelative(scan.started_at))}</span>}
          {scan.finished_at && <span>{T.finished(formatRelative(scan.finished_at))}</span>}
        </p>
      )}

      {scan.state !== 'idle' && (
        <div className={styles.progress}>
          {total !== null && processed !== null ? (
            <>
              <ProgressBar value={processed} max={total} label={T.progressLabel} className={styles.progressBar} />
              <span>{T.progress(processed, total)}</span>
            </>
          ) : (
            <span className={styles.indeterminate} role="progressbar" aria-label={T.progressLabel}>
              <span />
            </span>
          )}
        </div>
      )}

      {counts && (
        <dl className={styles.counts} aria-label={T.countsLabel}>
          {COUNT_ORDER.map((key) => (
            <div key={key} className={styles.count}>
              <dt title={T.countHints[key]}>{T.counts[key]}</dt>
              <dd>{formatCount(counts[key])}</dd>
            </div>
          ))}
        </dl>
      )}

      {counts && counts.missing > 0 && (
        <p className={styles.warning}>
          <span aria-hidden="true">{WARNING_GLYPH} </span>
          {T.missingWarning(counts.missing)}
        </p>
      )}

      {scan.state === 'failed' && scan.error && (
        <div className={styles.rawError} role="alert">
          <span className="visually-hidden">{T.failedError}</span>
          <code>{scan.error}</code>
        </div>
      )}

      {errorCount > 0 && (
        <details className={styles.errors}>
          <summary id={errorsId}>{T.errors(errorCount)}</summary>
          <ul aria-labelledby={errorsId} className={styles.logLines}>
            {scan.errors.slice(0, MAX_ERROR_LINES).map((line, i) => (
              <li key={i}>
                <code>{line}</code>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
