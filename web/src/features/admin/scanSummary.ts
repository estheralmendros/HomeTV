// Texts of a finished scan (prd2 §11 Scan step 4).
import type { ScanStatus } from '@/api/types';
import { S } from '@/strings.es';

const T = S.admin.scan.toast;

/**
 * "Escaneo terminado: 240 nuevos, 3 recuperados, 1 eliminado, 4 sin identificar" (only the
 * non-zero counts among new/changed/restored/removed/unmatched; "Sin cambios" when all are 0),
 * plus a second line when files are missing. A failed run reads "El escaneo ha fallado.".
 */
export function scanFinishedToast(scan: ScanStatus): string {
  if (scan.state === 'failed') return T.failed;
  const c = scan.counts;
  const parts = c
    ? [
        c.new > 0 && T.new(c.new),
        c.changed > 0 && T.changed(c.changed),
        c.restored > 0 && T.restored(c.restored),
        c.removed > 0 && T.removed(c.removed),
        c.unmatched > 0 && T.unmatched(c.unmatched),
      ].filter((p): p is string => typeof p === 'string')
    : [];
  const first = T.finished(parts.length > 0 ? parts.join(', ') : T.noChanges);
  return c && c.missing > 0 ? `${first}\n${T.missing(c.missing)}` : first;
}

/** A ScanStatus in an error's `details.scan` (409 scan_in_progress). */
export function isScanStatus(value: unknown): value is ScanStatus {
  return typeof value === 'object' && value !== null && 'state' in value && 'progress' in value;
}
