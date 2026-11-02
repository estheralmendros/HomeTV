// Folder browser of the add dialog: starts at the browse root,
// lists subfolders, "Subir", "Elegir esta carpeta". The server lists subfolders without opening
// them, so any click can fail: a failed listing always leaves a way back (the last good listing,
// or the root; "Reintentar" when the root itself fails).
import { useEffect, useState } from 'react';
import { errorMessage, isApiError } from '@/api/errors';
import type { BrowseResponse, BrowseRoot } from '@/api/types';
import { Spinner } from '@/components/Spinner';
import { WARNING_GLYPH } from '@/components/glyphs';
import { S } from '@/strings.es';
import { useBrowseQuery } from './adminQueries';
import styles from './Libraries.module.css';

const T = S.admin.libraries.dialog;

export interface FolderBrowserProps {
  root: BrowseRoot;
  onChoose: (path: string) => void;
}

function browseErrorText(err: unknown): string {
  if (isApiError(err, 'folder_not_found')) return T.folderGone;
  if (isApiError(err, 'folder_not_readable')) return T.folderUnreadable;
  return errorMessage(err);
}

export function FolderBrowser({ root, onChoose }: FolderBrowserProps) {
  /** The requested listing (`null` = the browse root). */
  const [path, setPath] = useState<string | null>(null);
  /** The last listing that loaded, with the key it was requested by. */
  const [lastGood, setLastGood] = useState<{ key: string | null; data: BrowseResponse } | null>(null);
  const listing = useBrowseQuery(path, { enabled: true });
  const data = listing.isError ? undefined : listing.data;

  // Only a settled listing counts: a revisited folder shows its cached data while it refetches,
  // and that refetch can still fail (the folder was removed meanwhile).
  const settled = listing.isSuccess && !listing.isFetching;
  useEffect(() => {
    if (settled) setLastGood({ key: path, data: listing.data });
  }, [settled, listing.data, path]);

  const failedPath = path ?? root.path;
  const isRoot = path === null || path === root.path;
  const current = data?.path ?? failedPath;

  return (
    <section className={styles.browser} aria-label={T.browserLabel}>
      <p className={styles.current}>{T.current(current)}</p>
      <div className={styles.browserBar}>
        <button
          type="button"
          className="button"
          disabled={!data || data.parent === null}
          onClick={() => data?.parent != null && setPath(data.parent)}
        >
          <span aria-hidden="true">↑ </span>
          {T.up}
        </button>
      </div>

      {listing.isError ? (
        <div className={styles.browserError}>
          <p className={styles.errorText} role="alert">
            <span aria-hidden="true">{WARNING_GLYPH} </span>
            {browseErrorText(listing.error)}
          </p>
          <div className={styles.browserBar}>
            {lastGood && lastGood.data.path !== failedPath && (
              <button type="button" className="button" onClick={() => setPath(lastGood.key)}>
                {T.back}
              </button>
            )}
            {isRoot ? (
              <button type="button" className="button" onClick={() => void listing.refetch()}>
                {S.common.retry}
              </button>
            ) : (
              <button type="button" className="button" onClick={() => setPath(null)}>
                {T.backToRoot}
              </button>
            )}
          </div>
        </div>
      ) : data ? (
        data.entries.length === 0 ? (
          <p className={styles.muted}>{T.noSubfolders}</p>
        ) : (
          <ul className={styles.entries}>
            {data.entries.map((entry) => (
              <li key={entry.path}>
                <button type="button" className={styles.entry} onClick={() => setPath(entry.path)}>
                  <span aria-hidden="true">▸ </span>
                  {entry.name}
                </button>
              </li>
            ))}
          </ul>
        )
      ) : (
        <Spinner />
      )}

      <div className={styles.browserChoose}>
        <button type="button" className="button" disabled={!data} onClick={() => data && onChoose(data.path)}>
          {T.choose}
        </button>
      </div>
    </section>
  );
}
