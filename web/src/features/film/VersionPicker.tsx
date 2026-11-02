import { useId } from 'react';
import type { ClientCaps, MediaFile } from '@/api/types';
import { versionOptionLabel } from '@/player/versions';
import { S } from '@/strings.es';
import styles from './FilmDetailPage.module.css';

export interface VersionPickerProps {
  /** In server order (never re-sorted). */
  files: MediaFile[];
  selectedId: number;
  caps: ClientCaps;
  onChange: (fileId: number) => void;
}

/** "Versión" select, shown only with more than one media file. */
export function VersionPicker({ files, selectedId, caps, onChange }: VersionPickerProps) {
  const id = useId();
  if (files.length <= 1) return null;
  return (
    <div className={styles.version}>
      <label htmlFor={id}>{S.film.version}</label>
      <select id={id} className={styles.select} value={selectedId} onChange={(event) => onChange(Number(event.target.value))}>
        {files.map((file) => (
          <option key={file.id} value={file.id}>
            {versionOptionLabel(file, caps)}
          </option>
        ))}
      </select>
    </div>
  );
}
