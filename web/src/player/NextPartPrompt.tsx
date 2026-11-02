import { useEffect, useRef } from 'react';
import { S } from '@/strings.es';
import styles from './Player.module.css';

export interface NextPartPromptProps {
  finishedPart: number;
  nextPart: number;
  onPlay: () => void;
  onBack: () => void;
}

/** "Parte 1 terminada" on `ended` of a non-final part; no countdown, no auto-advance. */
export function NextPartPrompt({ finishedPart, nextPart, onPlay, onBack }: NextPartPromptProps) {
  const playRef = useRef<HTMLButtonElement>(null);
  useEffect(() => playRef.current?.focus(), []);
  return (
    <div className={styles.prompt} role="dialog" aria-label={S.player.nextPart.label}>
      <p className={styles.promptTitle}>{S.player.nextPart.finished(finishedPart)}</p>
      <div className={styles.promptActions}>
        <button ref={playRef} type="button" className="button button-primary" onClick={onPlay}>
          {S.player.nextPart.play(nextPart)}
        </button>
        <button type="button" className="button" onClick={onBack}>
          {S.player.nextPart.back}
        </button>
      </div>
    </div>
  );
}
