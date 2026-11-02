import { useId } from 'react';
import type { MediaFile } from '@/api/types';
import { S } from '@/strings.es';
import styles from './Player.module.css';
import { versionLabel } from './versions';

/** "Otras versiones": one button per playable alternative; nothing when there is none. */
export function OtherVersions({ files, onTry }: { files: readonly MediaFile[]; onTry: (file: MediaFile) => void }) {
  const headingId = useId();
  if (files.length === 0) return null;
  return (
    <div role="group" aria-labelledby={headingId} className={styles.versions}>
      <p id={headingId} className={styles.versionsHeading}>
        {S.player.otherVersions}
      </p>
      {files.map((file) => (
        <button key={file.id} type="button" className="button" onClick={() => onTry(file)}>
          {S.player.tryVersion(versionLabel(file))}
        </button>
      ))}
    </div>
  );
}

/** Collapsed English diagnostic (server `detail`), never the main text. */
export function TechnicalDetails({ detail }: { detail: string | null | undefined }) {
  if (!detail) return null;
  return (
    <details className={styles.details}>
      <summary>{S.player.technicalDetails}</summary>
      <pre className={styles.detailsText}>{detail}</pre>
    </details>
  );
}
