import { useEffect, useRef } from 'react';
import type { EpisodeDetail } from '@/api/types';
import { Artwork } from '@/components/Artwork';
import { episodeTitle } from '@/lib/episodes';
import { episodeCode } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Player.module.css';

export interface NextEpisodePromptProps {
  /** The resolved next episode (D12). */
  episode: EpisodeDetail;
  /** Countdown seconds after `ended`; null before the end. */
  secondsLeft: number | null;
  onPlayNow: () => void;
  onCancel: () => void;
}

/** Bottom-right card in the last 30 s / on end (§9 step 10). */
export function NextEpisodePrompt({ episode, secondsLeft, onPlayNow, onCancel }: NextEpisodePromptProps) {
  const playRef = useRef<HTMLButtonElement>(null);
  const counting = secondsLeft != null;
  useEffect(() => {
    if (counting) playRef.current?.focus();
  }, [counting]);
  return (
    <div className={styles.prompt} role="dialog" aria-label={S.player.nextEpisode.label}>
      <p className={styles.promptTitle}>{S.player.nextEpisode.upNext(episodeCode(episode.season_number, episode.episode_number))}</p>
      <div className={styles.promptBody}>
        <div className={styles.promptStill}>
          <Artwork image={episode.still} title={episodeTitle(episode)} kind="still" />
        </div>
        <p>{episodeTitle(episode)}</p>
      </div>
      {counting && <p className={styles.promptCountdown}>{S.player.nextEpisode.countdown(secondsLeft)}</p>}
      <div className={styles.promptActions}>
        <button ref={playRef} type="button" className="button button-primary" onClick={onPlayNow}>
          {S.player.nextEpisode.playNow}
        </button>
        <button type="button" className="button" onClick={onCancel}>
          {S.player.nextEpisode.cancel}
        </button>
      </div>
    </div>
  );
}
