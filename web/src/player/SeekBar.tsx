import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react';
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

/** Keys with which a focused range input changes its value. */
const RANGE_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown']);

const percent = (value: number, total: number) => `${Math.min(100, Math.max(0, (value / total) * 100))}%`;

/**
 * `input[type=range]` "Posición" with a spoken aria-valuetext, buffered ranges and a hover time.
 * While a pointer drags it (or a range key is held) the bar shows the dragged value and seeks once,
 * on release (pointerup anywhere / keyup / blur): in HLS mode every far seek may restart the
 * server's ffmpeg run. A change with no pointer or key interaction (e.g. assistive tech) seeks at once.
 */
export function SeekBar({ current, duration, buffered, onSeek }: SeekBarProps) {
  const [hover, setHover] = useState<{ x: number; time: number } | null>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const interaction = useRef<'pointer' | 'key' | null>(null);
  const dragRef = useRef<number | null>(null);
  const onSeekRef = useRef(onSeek);
  onSeekRef.current = onSeek;
  const [pointerActive, setPointerActive] = useState(false);

  const commit = () => {
    interaction.current = null;
    setPointerActive(false);
    const target = dragRef.current;
    dragRef.current = null;
    setDrag(null);
    if (target !== null) onSeekRef.current(target);
  };
  const commitRef = useRef(commit);
  commitRef.current = commit;

  // A drag can end outside the bar: listen on the document while the pointer is down.
  useEffect(() => {
    if (!pointerActive) return undefined;
    const end = () => commitRef.current();
    document.addEventListener('pointerup', end);
    document.addEventListener('pointercancel', end);
    return () => {
      document.removeEventListener('pointerup', end);
      document.removeEventListener('pointercancel', end);
    };
  }, [pointerActive]);

  const total = duration != null && duration > 0 ? duration : null;
  const value = total ? Math.min(drag ?? current, total) : 0;
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
        onChange={(event) => {
          const seconds = Number(event.currentTarget.value);
          if (interaction.current === null) {
            onSeek(seconds);
            return;
          }
          dragRef.current = seconds;
          setDrag(seconds);
        }}
        onPointerDown={() => {
          interaction.current = 'pointer';
          setPointerActive(true);
        }}
        onKeyDown={(event: KeyboardEvent<HTMLInputElement>) => {
          if (RANGE_KEYS.has(event.key) && interaction.current === null) interaction.current = 'key';
        }}
        onKeyUp={() => {
          if (interaction.current === 'key') commit();
        }}
        onBlur={() => {
          if (interaction.current !== null) commit();
        }}
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
