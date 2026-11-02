import type { MediaFile } from '@/api/types';
import { WARNING_GLYPH } from '@/components/glyphs';
import { S } from '@/strings.es';
import { OtherVersions, TechnicalDetails } from './OtherVersions';
import styles from './Player.module.css';

export interface UnsupportedMessageProps {
  /** PlaybackUnsupported.reason (an unknown string renders the generic row). */
  reason: string;
  /** English diagnostic, only inside "Detalles técnicos". */
  detail: string | null;
  /** The current file's video codec ({CODEC}). */
  codec: string | null;
  /** playableAlternatives(media_files, current, caps, failedFileIds). */
  alternatives: readonly MediaFile[];
  onTry: (file: MediaFile) => void;
  onBack: () => void;
}

type Row = { heading: string; body: string | ((codec: string) => string) | null };

function rowFor(reason: string): Row {
  const rows = S.player.unsupported as Record<string, Row | undefined>;
  return (reason !== 'generic' ? rows[reason] : undefined) ?? S.player.unsupported.generic;
}

/** Reason-specific heading/body, collapsed detail, "Otras versiones", [Volver]. */
export function UnsupportedMessage({ reason, detail, codec, alternatives, onTry, onBack }: UnsupportedMessageProps) {
  const row = rowFor(reason);
  const body = typeof row.body === 'function' ? row.body((codec ?? S.common.unknown).toUpperCase()) : row.body;
  return (
    <div className={styles.screen}>
      <div className={styles.screenBox}>
        <h2 className={styles.screenHeading}>
          <span aria-hidden="true">{WARNING_GLYPH} </span>
          {row.heading}
        </h2>
        {body && <p className={styles.screenText}>{body}</p>}
        <TechnicalDetails detail={detail} />
        <OtherVersions files={alternatives} onTry={onTry} />
        <div className={styles.screenActions}>
          <button type="button" className="button" onClick={onBack}>
            {S.common.back}
          </button>
        </div>
      </div>
    </div>
  );
}
