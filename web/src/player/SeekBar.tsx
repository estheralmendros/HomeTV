import { useState, type CSSProperties, type PointerEvent } from 'react';
import { formatDuration, formatSpokenDuration } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './Player.module.css';

export interface SeekBarProps {
  current: number;
  /** Null while unknown (the bar is disabled). */
  duration: number | null;
  /** Buffered [start, end] ranges in seconds. */
  buffered: ReadonlyArray<readonly [number, number]>;
  onSeek: (seconds: number) => void;
}

const percent = (value: number, total: number) => `${Math.min(100, Math.max(0, (value / total) * 100))}%`;

/** `input[type=range]` "Posición" with a spoken aria-valuetext, buffered ranges and a hover time. */
export function SeekBar({ current, duration, buffered, onSeek }: SeekBarProps) {
  const [hover, setHover] = useState<{ x: number; time: number } | null>(null);
  const total = duration != null && duration > 0 ? duration : null;
  const value = total ? Math.min(current, total) : 0;
  const bufferedEnd = buffered.reduce((end, [start, stop]) => (start <= value + 1 ? Math.max(end, stop) : end), 0);

  const onPointerMove = (event: PointerEvent<HTMLInputElement>) => {
    if (!total || event.pointerType === 'touch') return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const x = Math.min(rect.width, Math.max(0, event.clientX - rect.left));
    setHover({ x, time: (x / rect.width) * total });
  };

  const style = { '--seek-progress': total ? percent(value, total) : '0%' } as CSSProperties;

  return (
    <div className={styles.seek} style={style}>
      <div
        className={styles.seekBuffered}
        data-testid="seek-buffered"
        style={{ width: total ? percent(bufferedEnd, total) : '0%' }}
        aria-hidden="true"
      />
      <input
        type="range"
        className={styles.seekInput}
        min={0}
        max={total ?? 0}
        step={1}
        value={Math.floor(value)}
        disabled={!total}
        aria-label={S.player.controls.position}
        aria-valuetext={total ? S.player.controls.positionValue(formatSpokenDuration(value), formatSpokenDuration(total)) : undefined}
        onChange={(event) => onSeek(Number(event.currentTarget.value))}
        onPointerMove={onPointerMove}
        onPointerLeave={() => setHover(null)}
      />
      {hover && (
        <span className={styles.seekTooltip} style={{ left: hover.x }} aria-hidden="true">
          {formatDuration(hover.time, { withHours: true })}
        </span>
      )}
    </div>
  );
}
