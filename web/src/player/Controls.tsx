// Player chrome (prd2 §9 step 11): back arrow + title on top; seek bar, play/pause, ∓10 s,
// time, volume + mute, subtitle/audio menus and fullscreen at the bottom. Every control has a
// Spanish aria-label from the catalog.
import type { ReactNode } from 'react';
import { formatDuration } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Player.module.css';
import { SeekBar } from './SeekBar';

export interface ControlsProps {
  visible: boolean;
  title: string;
  watched: boolean;
  onBack: () => void;
  /** The bottom bar (hidden on the error/unsupported screens). */
  showBottom: boolean;
  playing: boolean;
  onPlayPause: () => void;
  onSeekBy: (deltaSeconds: number) => void;
  current: number;
  duration: number | null;
  buffered: ReadonlyArray<readonly [number, number]>;
  onSeek: (seconds: number) => void;
  volume: number;
  muted: boolean;
  /** False on iOS/iPadOS (volume is read-only; mute stays). */
  volumeSlider: boolean;
  onVolume: (volume: number) => void;
  onToggleMute: () => void;
  subtitleMenu: ReactNode;
  audioMenu: ReactNode;
  fullscreen: { available: boolean; active: boolean; onToggle: () => void };
}

export function Controls(props: ControlsProps) {
  const { visible, title, watched, onBack, showBottom, playing, current, duration, fullscreen } = props;
  return (
    <div className={styles.chrome} data-hidden={!visible} data-testid="player-controls">
      <div className={styles.topBar}>
        <button type="button" className={styles.controlButton} aria-label={S.player.controls.back} onClick={onBack}>
          <span aria-hidden="true">←</span>
        </button>
        <h1 className={styles.title}>{title}</h1>
        {watched && (
          <span className={styles.watchedMark}>
            <span aria-hidden="true">✓ </span>
            {S.common.watched}
          </span>
        )}
      </div>
      {showBottom && (
        <div className={styles.bottomBar} role="group" aria-label={S.player.controls.label}>
          <SeekBar current={current} duration={duration} buffered={props.buffered} onSeek={props.onSeek} />
          <div className={styles.buttons}>
            <button
              type="button"
              className={styles.controlButton}
              aria-label={playing ? S.player.controls.pause : S.player.controls.play}
              onClick={props.onPlayPause}
            >
              <span aria-hidden="true">{playing ? '❚❚' : '▶'}</span>
            </button>
            <button type="button" className={styles.controlButton} aria-label={S.player.controls.rewind} onClick={() => props.onSeekBy(-10)}>
              <span aria-hidden="true">⟲10</span>
            </button>
            <button type="button" className={styles.controlButton} aria-label={S.player.controls.forward} onClick={() => props.onSeekBy(10)}>
              <span aria-hidden="true">10⟳</span>
            </button>
            <span className={styles.time} aria-label={S.player.controls.time}>
              {formatDuration(current, { withHours: true })} / {duration != null ? formatDuration(duration, { withHours: true }) : '–:––'}
            </span>
            <span className={styles.spacer} />
            <span className={styles.volume}>
              <button
                type="button"
                className={styles.controlButton}
                aria-label={props.muted ? S.player.controls.unmute : S.player.controls.mute}
                aria-pressed={props.muted}
                onClick={props.onToggleMute}
              >
                <span aria-hidden="true">{props.muted ? '🔇' : '🔊'}</span>
              </button>
              {props.volumeSlider && (
                <input
                  type="range"
                  className={styles.volumeSlider}
                  min={0}
                  max={1}
                  step={0.05}
                  value={props.muted ? 0 : props.volume}
                  aria-label={S.player.controls.volume}
                  onChange={(event) => props.onVolume(Number(event.currentTarget.value))}
                />
              )}
            </span>
            {props.subtitleMenu}
            {props.audioMenu}
            {fullscreen.available && (
              <button
                type="button"
                className={styles.controlButton}
                aria-label={fullscreen.active ? S.player.controls.exitFullscreen : S.player.controls.fullscreen}
                onClick={fullscreen.onToggle}
              >
                <span aria-hidden="true">⛶</span>
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
