// /admin/unmatched: filterable, paginated list of unmatched entries with a
// parsed summary and "Corregir identificación" (film/show rows → match, media_file rows → resolve).
// Filters and page live in the URL (`?library=&reason=&page=`).
import { useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { UNMATCHED_PAGE_SIZE } from '@/api/admin';
import { errorMessage } from '@/api/errors';
import type { LibraryType, UnmatchedEntry, UnmatchedQuery, UnmatchedReason } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
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
          <ul className={styles.rows} aria-label={T.title}>
            {data.items.map((entry) => (
              <UnmatchedRow key={entry.id} entry={entry} onFix={() => openFix(entry)} />
            ))}
          </ul>
        )
      ) : list.isError ? (
        <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />
      ) : (
        <Spinner />
      )}

      {dialog && <FixMatchDialog mode={dialog} onClose={() => setDialog(null)} />}
    </div>
  );
}

function UnmatchedRow({ entry, onFix }: { entry: UnmatchedEntry; onFix: () => void }) {
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
        {entry.detail && <p className={`${styles.muted} ${styles.detail}`}>{entry.detail}</p>}
        <p className={styles.muted}>{T.createdAt(formatDate(entry.created_at))}</p>
      </div>
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
    </li>
  );
}
