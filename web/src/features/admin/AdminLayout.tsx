// Admin section layout (lazy admin chunk, entered through RequireAdmin's PIN gate): tabs
// "Escaneo" / "Bibliotecas" / "Sin identificar" (badge = ScanStatus.unmatched_count) / "Perfiles"
// (prd2 §11, folder-selection-admin prd2 §2).
// It owns the scan polling, so a scan that finishes while another tab is open still
// invalidates the catalog and shows the summary toast.
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { invalidateAfter, queryKeys } from '@/api/queryKeys';
import type { ScanState, ScanStatus } from '@/api/types';
import { useToast } from '@/components/Toast';
import { formatCount } from '@/lib/format';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import styles from './Admin.module.css';
import { useScanQuery } from './adminQueries';
import { scanFinishedToast } from './scanSummary';

const TABS = [
  { to: 'scan', label: S.admin.tabs.scan },
  { to: 'libraries', label: S.admin.tabs.libraries },
  { to: 'unmatched', label: S.admin.tabs.unmatched },
  { to: 'profiles', label: S.admin.tabs.profiles },
] as const;

/** running → completed | failed: invalidate the catalog and `['admin','unmatched']`, toast the summary. */
function useScanFinished(scan: ScanStatus | undefined) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const previous = useRef<ScanState | undefined>(undefined);
  useEffect(() => {
    const before = previous.current;
    previous.current = scan?.state;
    if (!scan || before !== 'running' || (scan.state !== 'completed' && scan.state !== 'failed')) return;
    // The scan status itself was just fetched: no need to refetch it.
    const scanKey = JSON.stringify(queryKeys.admin.scan());
    for (const queryKey of invalidateAfter({ type: 'catalogChanged' })) {
      if (JSON.stringify(queryKey) !== scanKey) void queryClient.invalidateQueries({ queryKey });
    }
    toast.show(scanFinishedToast(scan), { durationMs: 8000 });
  }, [scan, queryClient, toast]);
}

export function AdminLayout() {
  useDocumentTitle(S.titles.admin);
  const { data: scan } = useScanQuery({ poll: true });
  useScanFinished(scan);
  const unmatched = scan?.unmatched_count ?? 0;

  return (
    <section className="page">
      <h1>{S.admin.title}</h1>
      <nav className={styles.tabs} aria-label={S.admin.tabsLabel}>
        {TABS.map((tab) => (
          <NavLink key={tab.to} to={tab.to} className={({ isActive }) => (isActive ? `${styles.tab} ${styles.active}` : styles.tab)}>
            {tab.label}
            {tab.to === 'unmatched' && unmatched > 0 && <span className={styles.badge}>{formatCount(unmatched)}</span>}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </section>
  );
}
