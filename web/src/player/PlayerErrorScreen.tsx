import type { ItemType, MediaFile } from '@/api/types';
import { S } from '@/strings.es';
import { OtherVersions, TechnicalDetails } from './OtherVersions';
import styles from './Player.module.css';
import type { PlayerError } from './playerMachine';

export interface PlayerErrorScreenProps {
  error: PlayerError;
  itemType: ItemType;
  /** Offered for `version_failed` (same part, playable, not failed in this mount). */
  alternatives: readonly MediaFile[];
  onRetry: () => void;
  onTry: (file: MediaFile) => void;
  onBack: () => void;
}

/**
 * §9 step 15 error kinds: busy / prepare_timeout / decode / failed → message + [Reintentar]
 * [Volver] ("Detalles técnicos" for a transcoder detail); gone → the item-type copy + [Volver];
 * version_failed → no Retry, [Probar …] per alternative + [Volver].
 */
export function PlayerErrorScreen({ error, itemType, alternatives, onRetry, onTry, onBack }: PlayerErrorScreenProps) {
  const gone = error.kind === 'gone';
  const versionFailed = error.kind === 'version_failed';
  return (
    <div className={styles.screen}>
      <div className={styles.screenBox} role="alert">
        <p className={styles.screenHeading}>
          <span aria-hidden="true">⚠ </span>
          {gone ? S.player.gone[itemType] : error.message}
        </p>
        {gone && error.code === 'file_missing' && <p className={styles.screenText}>{error.message}</p>}
        {!versionFailed && <TechnicalDetails detail={error.detail} />}
        {versionFailed && <OtherVersions files={alternatives} onTry={onTry} />}
        <div className={styles.screenActions}>
          {!gone && !versionFailed && (
            <button type="button" className="button button-primary" onClick={onRetry}>
              {S.common.retry}
            </button>
          )}
          <button type="button" className="button" onClick={onBack}>
            {S.common.back}
          </button>
        </div>
      </div>
    </div>
  );
}
