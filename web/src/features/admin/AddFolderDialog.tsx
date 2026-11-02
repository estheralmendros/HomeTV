// "Añadir carpeta a Películas/Series": a typed path, plus the
// folder browser when the server's browse root exists. Only `not_absolute` and `duplicate` are
// checked here; the server validates the rest on save.
import { useId, useState, type FormEvent } from 'react';
import type { BrowseRoot, LibraryType } from '@/api/types';
import { Modal } from '@/components/Modal';
import { WARNING_GLYPH } from '@/components/glyphs';
import { cx } from '@/lib/cx';
import { S } from '@/strings.es';
import adminStyles from './Admin.module.css';
import { FolderBrowser } from './FolderBrowser';
import { checkNewPath, type LibraryDraft } from './libraryDraft';
import styles from './Libraries.module.css';

const T = S.admin.libraries;

export interface AddFolderDialogProps {
  library: LibraryType;
  draft: LibraryDraft;
  root: BrowseRoot;
  phone: boolean;
  onAdd: (path: string) => void;
  onClose: () => void;
}

export function AddFolderDialog({ library, draft, root, phone, onAdd, onClose }: AddFolderDialogProps) {
  const [value, setValue] = useState('');
  const [error, setError] = useState<'not_absolute' | 'duplicate' | null>(null);
  const inputId = useId();
  const errorId = useId();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (value.trim() === '') return;
    const result = checkNewPath(draft, value);
    if ('error' in result) {
      setError(result.error);
      return;
    }
    onAdd(result.path);
    onClose();
  };

  return (
    <Modal title={T.dialog.title(T.sections[library])} onClose={onClose} className={cx(styles.dialog, phone && styles.dialogPhone)}>
      <form className={adminStyles.form} onSubmit={submit} data-layout={phone ? 'phone' : 'desktop'}>
        <div className={adminStyles.formField}>
          <label htmlFor={inputId}>{T.dialog.pathLabel}</label>
          <input
            id={inputId}
            className={cx(adminStyles.input, styles.pathInput)}
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder={T.dialog.placeholder}
            value={value}
            aria-invalid={error !== null}
            aria-describedby={error ? errorId : undefined}
            onChange={(event) => {
              setValue(event.target.value);
              setError(null);
            }}
          />
          {error && (
            <p id={errorId} className={adminStyles.error} role="alert">
              <span aria-hidden="true">{WARNING_GLYPH} </span>
              {T.errors[error]}
            </p>
          )}
        </div>
        {root.available ? (
          <FolderBrowser
            root={root}
            onChoose={(path) => {
              setValue(path);
              setError(null);
            }}
          />
        ) : (
          <p className={styles.muted}>{T.dialog.browseUnavailable(root.path)}</p>
        )}
        <div className={adminStyles.actions}>
          <button type="button" className="button" onClick={onClose}>
            {S.common.cancel}
          </button>
          <button type="submit" className="button button-primary" disabled={value.trim() === ''}>
            {T.dialog.add}
          </button>
        </div>
      </form>
    </Modal>
  );
}
