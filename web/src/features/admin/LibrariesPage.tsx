// /admin/libraries "Bibliotecas" (folder-selection-admin prd2 §3): the Películas and Series
// folders with live status and title counts, a local draft (add / remove / undo), and one save:
// POST …/plan → a confirmation built only from the server's plan (when it lists consequences) →
// PUT, which starts a `libraries` scan. Every result shows on the page, never in a dialog.
import { useQueryClient } from '@tanstack/react-query';
import { useId, useReducer, useState } from 'react';
import { planLibraries, saveLibraries } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import { queryKeys } from '@/api/queryKeys';
import type {
  LibrariesPlan,
  LibrariesPlanRequest,
  LibrariesResponse,
  LibraryFolder,
  LibraryFolderError,
  LibraryType,
} from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { ErrorState } from '@/components/ErrorState';
import { WARNING_GLYPH } from '@/components/glyphs';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { useToast } from '@/components/Toast';
import { cx } from '@/lib/cx';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { S } from '@/strings.es';
import adminStyles from './Admin.module.css';
import { AddFolderDialog } from './AddFolderDialog';
import { useLibrariesQuery, useScanQuery } from './adminQueries';
import styles from './Libraries.module.css';
import {
  LIBRARIES,
  draftReducer,
  initialDraft,
  isDirty,
  planHasRelinks,
  planIsEmpty,
  savedLists,
  toRequest,
  type DraftAction,
  type DraftRow,
} from './libraryDraft';
import { isScanStatus } from './scanSummary';

const T = S.admin.libraries;

interface Confirming {
  plan: LibrariesPlan;
  request: LibrariesPlanRequest;
}

/** `details.folders` of a 422 `invalid_library_folders`, or null when it is not that shape. */
function folderErrorsOf(details: Record<string, unknown>): LibraryFolderError[] | null {
  const folders = details.folders;
  if (!Array.isArray(folders)) return null;
  const valid = folders.every(
    (e: unknown) =>
      typeof e === 'object' &&
      e !== null &&
      typeof (e as LibraryFolderError).path === 'string' &&
      typeof (e as LibraryFolderError).code === 'string' &&
      ((e as LibraryFolderError).library === 'films' || (e as LibraryFolderError).library === 'shows'),
  );
  return valid ? (folders as LibraryFolderError[]) : null;
}

function folderErrorText(error: LibraryFolderError): string {
  if (error.code === 'overlaps_folder') return T.errors.overlaps_folder(error.other);
  const text = (T.errors as Record<string, unknown>)[error.code];
  return typeof text === 'string' ? text : S.errors.unknown(error.code);
}

const keyOf = (library: LibraryType, path: string) => `${library}\u0000${path}`;

export function LibrariesPage() {
  const libraries = useLibrariesQuery();
  const { data: scan } = useScanQuery();
  const phone = useMediaQuery(PHONE_QUERY);
  const queryClient = useQueryClient();
  const runAdmin = useAdminAction();
  const toast = useToast();

  const [draft, dispatch] = useReducer(draftReducer, { films: [], shows: [] }, initialDraft);
  const [synced, setSynced] = useState<LibrariesResponse | undefined>(undefined);
  const [folderErrors, setFolderErrors] = useState<LibraryFolderError[]>([]);
  const [scanAlert, setScanAlert] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [adding, setAdding] = useState<LibraryType | null>(null);

  // The first GET initializes the draft; later data (refetch, save) resets it only while clean.
  const data = libraries.data;
  if (data && data !== synced) {
    setSynced(data);
    if (synced === undefined || !isDirty(draft)) dispatch({ type: 'reset', saved: savedLists(data) });
  }

  const dirty = isDirty(draft);
  const scanRunning = scan?.state === 'running';

  const clearResults = () => {
    setFolderErrors([]);
    setScanAlert(false);
  };

  const change = (action: DraftAction) => {
    dispatch(action);
    clearResults();
  };

  const handleError = (err: unknown) => {
    if (isAdminActionCancelled(err)) return; // RequireAdmin leaves the page (prd2 "Keypad cancel").
    if (isApiError(err, 'invalid_library_folders')) {
      const folders = folderErrorsOf(err.details);
      if (folders) {
        setFolderErrors(folders);
        return;
      }
    }
    if (isApiError(err, 'scan_in_progress')) {
      const current = err.details.scan;
      if (isScanStatus(current)) queryClient.setQueryData(queryKeys.admin.scan(), current);
      setScanAlert(true);
      return;
    }
    toast.show(errorMessage(err));
  };

  /** Step 4: the PUT, with `confirm_relinks` when the plan lists moves or relayouts. */
  const submit = async ({ plan, request }: Confirming) => {
    setConfirming(null);
    setBusy(true);
    try {
      const response = await runAdmin(
        () => saveLibraries({ ...request, confirm_relinks: planHasRelinks(plan) }),
        T.save,
      );
      queryClient.setQueryData(queryKeys.admin.libraries(), response);
      if (response.scan) queryClient.setQueryData(queryKeys.admin.scan(), response.scan);
      dispatch({ type: 'reset', saved: savedLists(response) });
      clearResults();
      toast.show(response.scan ? T.saved : T.savedNoScan);
      void runInvalidation(queryClient, { type: 'librariesChanged' });
    } catch (err) {
      // The plan was stale: plan again and let the admin confirm what it lists now.
      if (isApiError(err, 'relinks_unconfirmed')) await preview(request, true);
      else handleError(err);
    } finally {
      setBusy(false);
    }
  };

  /** Step 3: the plan; an empty plan saves at once, unless a confirmation is required. */
  const preview = async (request: LibrariesPlanRequest, mustConfirm: boolean) => {
    setBusy(true);
    clearResults();
    try {
      const plan = await runAdmin(() => planLibraries(request), T.save);
      if (planIsEmpty(plan) && !mustConfirm) await submit({ plan, request });
      else setConfirming({ plan, request });
    } catch (err) {
      handleError(err);
    } finally {
      setBusy(false);
    }
  };

  if (!data || !synced) {
    return (
      <div className={adminStyles.panel}>
        {libraries.isError ? (
          <ErrorState message={errorMessage(libraries.error)} onRetry={() => void libraries.refetch()} />
        ) : (
          <Spinner />
        )}
      </div>
    );
  }

  const folders = new Map<string, LibraryFolder>();
  for (const library of LIBRARIES) for (const folder of data[library]) folders.set(keyOf(library, folder.path), folder);

  const rowErrors = new Map<string, LibraryFolderError>();
  const unmatchedErrors: LibraryFolderError[] = [];
  for (const error of folderErrors) {
    const key = keyOf(error.library, error.path);
    const present = draft[error.library].some((r) => r.path === error.path && !r.removed);
    if (present && !rowErrors.has(key)) rowErrors.set(key, error);
    else unmatchedErrors.push(error);
  }

  const fullWidth = phone ? 'true' : undefined;
  const saveButton = (
    <button
      type="button"
      className="button button-primary"
      data-full-width={fullWidth}
      disabled={!dirty || busy || scanRunning}
      onClick={() => void preview(toRequest(draft), false)}
    >
      {T.save}
    </button>
  );
  const discardButton = (
    <button
      type="button"
      className="button"
      data-full-width={fullWidth}
      disabled={!dirty || busy}
      onClick={() => change({ type: 'reset', saved: savedLists(data) })}
    >
      {T.discard}
    </button>
  );

  return (
    <div className={cx(adminStyles.panel, styles.page)} data-testid="libraries-page" data-layout={phone ? 'phone' : 'desktop'}>
      {data.ignored_env.length > 0 && (
        <p className={styles.info} role="note">
          <span aria-hidden="true">ⓘ </span>
          {T.ignoredEnv(data.ignored_env)}
        </p>
      )}

      {LIBRARIES.map((library) => (
        <LibrarySection
          key={library}
          library={library}
          rows={draft[library]}
          folders={folders}
          rowErrors={rowErrors}
          phone={phone}
          onChange={change}
          onAdd={() => setAdding(library)}
        />
      ))}

      <div className={cx(styles.saveBar, phone && styles.saveBarPhone)}>
        <div className={styles.saveMessages}>
          {scanAlert ? (
            <p className={adminStyles.warning} role="alert">
              {T.scanInProgress}
            </p>
          ) : (
            scanRunning && <p className={styles.muted}>{T.scanInProgress}</p>
          )}
          {folderErrors.length > 0 && (
            <p className={adminStyles.error} role="alert">
              <span aria-hidden="true">{WARNING_GLYPH} </span>
              {T.errorSummary}
            </p>
          )}
          {unmatchedErrors.length > 0 && (
            <ul className={styles.unmatchedErrors} aria-label={T.errorSummary}>
              {unmatchedErrors.map((error, i) => (
                <li key={i}>
                  <span className={styles.mono}>
                    {T.sections[error.library]} · {error.path}
                  </span>
                  : {folderErrorText(error)}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className={styles.saveButtons}>
          {busy && <Spinner />}
          {phone ? (
            <>
              {saveButton}
              {discardButton}
            </>
          ) : (
            <>
              {discardButton}
              {saveButton}
            </>
          )}
        </div>
      </div>

      {adding && (
        <AddFolderDialog
          library={adding}
          draft={draft}
          root={data.browse_root}
          phone={phone}
          onAdd={(path) => change({ type: 'add', library: adding, path })}
          onClose={() => setAdding(null)}
        />
      )}
      {confirming && (
        <ConfirmDialog
          plan={confirming.plan}
          graceDays={data.missing_grace_days}
          onCancel={() => setConfirming(null)}
          onSubmit={() => void submit(confirming)}
        />
      )}
    </div>
  );
}

interface LibrarySectionProps {
  library: LibraryType;
  rows: DraftRow[];
  folders: Map<string, LibraryFolder>;
  rowErrors: Map<string, LibraryFolderError>;
  phone: boolean;
  onChange: (action: DraftAction) => void;
  onAdd: () => void;
}

function LibrarySection({ library, rows, folders, rowErrors, phone, onChange, onAdd }: LibrarySectionProps) {
  const headingId = useId();
  return (
    <section className={styles.section} aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.sectionTitle}>
        {T.sections[library]}
      </h2>
      {rows.length === 0 ? (
        <p className={styles.muted}>{T.empty}</p>
      ) : (
        <ul className={styles.rows}>
          {rows.map((row) => (
            <FolderRow
              key={`${row.origin}:${row.path}`}
              library={library}
              row={row}
              folder={row.origin === 'saved' ? folders.get(keyOf(library, row.path)) : undefined}
              error={row.removed ? undefined : rowErrors.get(keyOf(library, row.path))}
              phone={phone}
              onChange={onChange}
            />
          ))}
        </ul>
      )}
      <button type="button" className={cx('button', styles.addButton)} data-full-width={phone ? 'true' : undefined} onClick={onAdd}>
        <span aria-hidden="true">+ </span>
        {T.addFolder}
      </button>
    </section>
  );
}

interface FolderRowProps {
  library: LibraryType;
  row: DraftRow;
  folder: LibraryFolder | undefined;
  error: LibraryFolderError | undefined;
  phone: boolean;
  onChange: (action: DraftAction) => void;
}

function FolderRow({ library, row, folder, error, phone, onChange }: FolderRowProps) {
  const pathId = useId();
  const errorId = useId();
  const { path } = row;
  return (
    <li className={cx(styles.row, phone && styles.rowPhone)} aria-labelledby={pathId} aria-describedby={error ? errorId : undefined}>
      <span id={pathId} className={cx(styles.path, styles.mono)} data-testid="row-path">
        {row.removed ? <s>{path}</s> : path}
      </span>
      <span className={styles.meta} data-testid="row-meta">
        {row.origin === 'added' ? (
          <span className={cx(styles.chip, styles.chipNew)}>{T.added}</span>
        ) : row.removed ? (
          <span className={cx(styles.chip, styles.chipRemoved)}>{T.removed}</span>
        ) : (
          folder && (
            <>
              <span className={styles.status} data-status={folder.status}>
                <span aria-hidden="true">● </span>
                {T.status[folder.status]}
              </span>
              <span className={styles.count}>{T.titleCount(folder.title_count)}</span>
            </>
          )
        )}
        {row.removed ? (
          <button type="button" className="button" aria-label={T.undoLabel(path)} onClick={() => onChange({ type: 'undo', library, path })}>
            {T.undo}
          </button>
        ) : (
          <button
            type="button"
            className="button"
            aria-label={T.removeLabel(path)}
            onClick={() => onChange({ type: 'remove', library, path })}
          >
            {T.remove}
          </button>
        )}
      </span>
      {error && (
        <p id={errorId} className={cx(adminStyles.error, styles.rowError)} role="alert">
          <span aria-hidden="true">{WARNING_GLYPH} </span>
          {folderErrorText(error)}
        </p>
      )}
    </li>
  );
}

interface ConfirmDialogProps {
  plan: LibrariesPlan;
  graceDays: number;
  onCancel: () => void;
  onSubmit: () => void;
}

/** Built only from the server's plan; relink groups first (they cannot be undone). */
function ConfirmDialog({ plan, graceDays, onCancel, onSubmit }: ConfirmDialogProps) {
  const C = T.confirm;
  const lib = (library: LibraryType) => T.sections[library];
  const onlyRemoved = plan.removed.length > 0 && plan.covered.length === 0 && !planHasRelinks(plan);
  return (
    <Modal title={onlyRemoved ? C.title(plan.removed.length) : C.titleChanges} onClose={onCancel} className={styles.dialog}>
      <div className={styles.confirm}>
        {plan.moved.length > 0 && (
          <div className={styles.group}>
            <ul className={styles.items}>
              {plan.moved.map((m, i) => (
                <li key={i} className={styles.mono}>
                  {m.from_path === m.to_path
                    ? C.movedSameItem(lib(m.from_library), lib(m.to_library), m.to_path, m.title_count)
                    : C.movedItem(lib(m.from_library), m.from_path, lib(m.to_library), m.to_path, m.title_count)}
                </li>
              ))}
            </ul>
            <p className={adminStyles.warning} role="alert">
              <span aria-hidden="true">{WARNING_GLYPH} </span>
              {C.movedBody}
            </p>
          </div>
        )}
        {plan.relayout.length > 0 && (
          <div className={styles.group}>
            <ul className={styles.items}>
              {plan.relayout.map((r, i) => (
                <li key={i} className={styles.mono}>
                  {C.relayoutItem(r.from_path, r.to_path, r.title_count)}
                </li>
              ))}
            </ul>
            <p className={adminStyles.warning} role="alert">
              <span aria-hidden="true">{WARNING_GLYPH} </span>
              {C.relayoutBody}
            </p>
          </div>
        )}
        {plan.removed.length > 0 && (
          <div className={styles.group}>
            <ul className={styles.items}>
              {plan.removed.map((r, i) => (
                <li key={i} className={styles.mono}>
                  {C.item(lib(r.library), r.path, r.title_count)}
                </li>
              ))}
            </ul>
            <p>{C.body(graceDays)}</p>
          </div>
        )}
        {plan.covered.length > 0 && (
          <div className={styles.group}>
            <ul className={styles.items}>
              {plan.covered.map((c, i) => (
                <li key={i} className={styles.mono}>
                  {C.coveredItem(lib(c.library), c.path, c.by)}
                </li>
              ))}
            </ul>
            <p>{C.coveredBody}</p>
          </div>
        )}
      </div>
      <div className={adminStyles.actions}>
        <button type="button" className="button" onClick={onCancel}>
          {S.common.cancel}
        </button>
        <button type="button" className="button button-primary" onClick={onSubmit}>
          {onlyRemoved ? C.submit : C.submitChanges}
        </button>
      </div>
    </Modal>
  );
}
