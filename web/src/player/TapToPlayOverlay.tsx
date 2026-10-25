import { useEffect, useRef } from 'react';
import { PLAY_GLYPH } from '@/components/glyphs';
import { S } from '@/strings.es';
import styles from './Player.module.css';

/**
 * `blocked` (§9 step 5a): a large focused play glyph "Toca para reproducir". The click handler must call
 * `video.play()` synchronously inside the user gesture (PlaybackSession.play does).
 */
export function TapToPlayOverlay({ onPlay }: { onPlay: () => void }) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return (
    <div className={styles.overlay}>
      <button ref={ref} type="button" className={styles.tapToPlay} onClick={onPlay} aria-label={S.player.tapToPlay}>
        <span className={styles.tapIcon} aria-hidden="true">
          {PLAY_GLYPH}
        </span>
        <span aria-hidden="true">{S.player.tapToPlay}</span>
      </button>
    </div>
  );
}
