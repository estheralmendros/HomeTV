// /admin/unmatched: filterable, paginated list of unmatched entries with a
// parsed summary, "Corregir identificación" (film/show rows → match, media_file rows → resolve) and
// "Quitar" (a square trash-icon button, named by aria-label; confirmed DELETE of the entry only, for entries stuck after a file was removed or renamed).
// Filters and page live in the URL (`?library=&reason=&page=`).
import { useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { deleteUnmatched, UNMATCHED_PAGE_SIZE } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import type { LibraryType, UnmatchedEntry, UnmatchedQuery, UnmatchedReason } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { formatDate } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Admin.module.css';
import { useUnmatchedQuery } from './adminQueries';
import { entryToDialogMode, parsedSummary } from './entryToDialogMode';
import { FixMatchDialog } from './FixMatchDialog';
import type { FixMatchMode } from './fixMatchTypes';

const T = S.admin.unmatched;

export const UNMATCHED_REASONS: UnmatchedReason[] = [
  'no_title', 'no_season', 'no_episode_number', 'multi_part', 'probe_failed', 'no_tmdb_results', 'low_confidence',
];
const LIBRARIES: LibraryType[] = ['films', 'shows'];

export interface UnmatchedParams {
  library: LibraryType | null;
  reason: UnmatchedReason | null;
  page: number;
}

/** URL → params (unknown values are ignored, page ≥ 1). */
export function parseUnmatchedParams(search: URLSearchParams): UnmatchedParams {
  const library = search.get('library');
  const reason = search.get('reason');
  const page = Number(search.get('page'));
  return {
    library: LIBRARIES.includes(library as LibraryType) ? (library as LibraryType) : null,
    reason: UNMATCHED_REASONS.includes(reason as UnmatchedReason) ? (reason as UnmatchedReason) : null,
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  };
}

function toSearch(params: UnmatchedParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.library) search.set('library', params.library);
  if (params.reason) search.set('reason', params.reason);
  if (params.page > 1) search.set('page', String(params.page));
  return search;
}

function toQuery(params: UnmatchedParams): UnmatchedQuery {
  const query: UnmatchedQuery = {};
  if (params.library) query.library = params.library;
  if (params.reason) query.reason = params.reason;
  query.page = params.page;
  return query;
}

/** The row's name in "Quitar" labels: the title, or the file name of a media_file target. */
function entryName(entry: UnmatchedEntry): string {
  const { target } = entry;
  if (target.type === 'media_file') return target.relative_path.split('/').pop() ?? target.relative_path;
  return S.common.titleWithYear(target.title, target.year);
}

function reasonText(entry: UnmatchedEntry): string {
  const text = T.reasons[entry.reason] ?? entry.reason;
  if (entry.reason === 'multi_part' && entry.parsed?.kind === 'film' && entry.parsed.part !== null) {
    return T.reasonWithPart(text, S.versions.part(entry.parsed.part));
  }
  return text;
}

export function UnmatchedPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = parseUnmatchedParams(searchParams);
  const list = useUnmatchedQuery(toQuery(params));
  const runAdmin = useAdminAction();
  const [dialog, setDialog] = useState<FixMatchMode | null>(null);
  const [removing, setRemoving] = useState<UnmatchedEntry | null>(null);
  const queryClient = useQueryClient();
  const id = useId();

  const update = (patch: Partial<UnmatchedParams>) => {
    // A filter change starts again from page 1.
    const next = { ...params, page: 1, ...patch };
    setSearchParams(toSearch(next), { replace: true });
  };

  const openFix = (entry: UnmatchedEntry) => {
    runAdmin(async () => setDialog(entryToDialogMode(entry)), S.fixMatch.action).catch((err: unknown) => {
      if (!isAdminActionCancelled(err)) console.warn('fix match gate failed', err);
    });
  };

  const onRemove = async (entry: UnmatchedEntry) => {
    try {
      await runAdmin(() => deleteUnmatched(entry.id), T.removeLabel(entryName(entry)));
    } catch (err) {
      // Already gone (e.g. removed from another tab): same outcome as a success.
      if (!isApiError(err, 'not_found')) throw err;
    }
    setRemoving(null);
    void runInvalidation(queryClient, { type: 'unmatchedChanged' });
  };

  const data = list.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / (data.page_size || UNMATCHED_PAGE_SIZE))) : 1;
  const filtered = params.library !== null || params.reason !== null;

  return (
    <div className={styles.panel}>
      <h2 className="visually-hidden">{T.title}</h2>
      <div className={styles.filters}>
        <div className={styles.field}>
          <label htmlFor={`${id}-library`}>{T.library}</label>
          <select
            id={`${id}-library`}
            className={styles.input}
            value={params.library ?? ''}
            onChange={(e) => update({ library: (e.target.value || null) as LibraryType | null })}
          >
            <option value="">{T.allLibraries}</option>
            {LIBRARIES.map((library) => (
              <option key={library} value={library}>
                {T.libraries[library]}
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label htmlFor={`${id}-reason`}>{T.reason}</label>
          <select
            id={`${id}-reason`}
            className={styles.input}
            value={params.reason ?? ''}
            onChange={(e) => update({ reason: (e.target.value || null) as UnmatchedReason | null })}
          >
            <option value="">{T.allReasons}</option>
            {UNMATCHED_REASONS.map((reason) => (
              <option key={reason} value={reason}>
                {T.reasons[reason]}
              </option>
            ))}
          </select>
        </div>
        {data && (data.items.length > 0 || params.page > 1) && (
          <nav className={styles.pager} aria-label={T.pagination}>
            <button
              type="button"
              className="button"
              disabled={params.page <= 1}
              onClick={() => setSearchParams(toSearch({ ...params, page: params.page - 1 }), { replace: true })}
            >
              {S.common.previous}
            </button>
            <span>{T.pageOf(params.page, pages)}</span>
            <button
              type="button"
              className="button"
              disabled={params.page >= pages}
              onClick={() => setSearchParams(toSearch({ ...params, page: params.page + 1 }), { replace: true })}
            >
              {S.common.next}
            </button>
          </nav>
        )}
      </div>

      {data ? (
        data.items.length === 0 ? (
          <EmptyState message={filtered ? T.emptyFiltered : T.empty} />
        ) : (
          <ul className={styles.rows} role="list" aria-label={T.title}>
            {data.items.map((entry) => (
              <UnmatchedRow
                key={entry.id}
                entry={entry}
                onFix={() => openFix(entry)}
                onRemove={() => setRemoving(entry)}
              />
            ))}
          </ul>
        )
      ) : list.isError ? (
        <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />
      ) : (
        <Spinner />
      )}

      {dialog && <FixMatchDialog mode={dialog} onClose={() => setDialog(null)} />}
      {removing && <RemoveDialog onCancel={() => setRemoving(null)} onConfirm={() => onRemove(removing)} />}
    </div>
  );
}

function UnmatchedRow({ entry, onFix, onRemove }: { entry: UnmatchedEntry; onFix: () => void; onRemove: () => void }) {
  const hintId = useId();
  const { target } = entry;
  const probeFailed = entry.reason === 'probe_failed';
  const relativePath = target.type === 'media_file' ? target.relative_path : '';
  const summary = parsedSummary(entry.parsed, relativePath);

  return (
    <li className={styles.row}>
      <div className={styles.rowMain}>
        <div className={styles.rowHead}>
          <span>{T.libraries[entry.library] ?? entry.library}</span>
          <span aria-hidden="true">│</span>
          <span>{reasonText(entry)}</span>
        </div>
        <div className={styles.target}>
          {target.type === 'media_file' ? (
            <span>{target.relative_path}</span>
          ) : (
            <Link to={target.type === 'film' ? `/films/${target.id}` : `/shows/${target.id}`}>
              {S.common.titleWithYear(target.title, target.year)}
            </Link>
          )}
        </div>
        {summary && (
          <p className={styles.muted}>
            <span>{T.parsedLabel}: </span>
            <span>{summary}</span>
          </p>
        )}
        {target.type !== 'media_file' && entry.files.length > 0 && (
          <>
            <ul
              className={`${styles.muted} ${styles.detail} ${styles.files}`}
              role="list"
              aria-label={T.filesLabel}
            >
              {entry.files.map((path) => (
                <li key={path}>{path}</li>
              ))}
            </ul>
            {entry.files_total > entry.files.length && (
              <p className={styles.muted}>{T.filesMore(entry.files_total - entry.files.length)}</p>
            )}
          </>
        )}
        {entry.detail && <p className={`${styles.muted} ${styles.detail}`}>{entry.detail}</p>}
        <p className={styles.muted}>{T.createdAt(formatDate(entry.created_at))}</p>
      </div>
      <span className={styles.rowActions}>
        <span title={probeFailed ? T.probeFailedHint : undefined}>
          <button
            type="button"
            className="button"
            disabled={probeFailed}
            aria-describedby={probeFailed ? hintId : undefined}
            onClick={onFix}
          >
            {S.fixMatch.action}
          </button>
          {probeFailed && (
            <span id={hintId} className="visually-hidden">
              {T.probeFailedHint}
            </span>
          )}
        </span>
        <button
          type="button"
          className={`button ${styles.iconButton}`}
          aria-label={T.removeLabel(entryName(entry))}
          title={T.removeHint}
          onClick={onRemove}
        >
          <TrashIcon />
        </button>
      </span>
    </li>
  );
}

/** Inline SVG trash bin (decorative: its button carries the name). */
function TrashIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width={20}
      height={20}
      aria-hidden="true"
      focusable="false"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
    </svg>
  );
}

function RemoveDialog({ onCancel, onConfirm }: { onCancel: () => void; onConfirm: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
    } catch (err) {
      setBusy(false);
      if (!isAdminActionCancelled(err)) setError(errorMessage(err));
    }
  };
  return (
    <Modal title={T.removeTitle} onClose={onCancel}>
      <p>{T.removeBody}</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <button type="button" className="button" onClick={onCancel}>
          {S.common.cancel}
        </button>
        <button type="button" className="button button-primary" disabled={busy} onClick={() => void confirm()}>
          {T.removeAction}
        </button>
      </div>
    </Modal>
  );
}
