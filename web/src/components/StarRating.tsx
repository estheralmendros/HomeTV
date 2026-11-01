// Stars (ratings prd2 FR-W6): one inline SVG star (viewBox 0 0 24 24; the half is a clipPath on
// the filled shape), never a font glyph or emoji. StarDisplay is an image with the text
// equivalent; StarInput is a keyboard/pointer slider from ½ to 5 in ½ steps.
import { useId, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { cx } from '@/lib/cx';
import { starFills, starsFromPointer, stepStars, MAX_STARS, MIN_STARS, type StarFill } from '@/lib/stars';
import { S } from '@/strings.es';
import styles from './StarRating.module.css';

const STAR_PATH = 'M12 2.6l2.9 5.9 6.5.95-4.7 4.6 1.1 6.5L12 17.5l-5.8 3.05 1.1-6.5-4.7-4.6 6.5-.95z';

export type StarSize = 'sm' | 'md';

/** One star; `data-fill` exposes full/half/empty to tests. */
export function StarIcon({ fill, className }: { fill: StarFill; className?: string }) {
  const clipId = useId();
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-fill={fill} className={cx(styles.star, className)}>
      {fill === 'half' && (
        <defs>
          <clipPath id={clipId}>
            <rect x="0" y="0" width="12" height="24" />
          </clipPath>
        </defs>
      )}
      <path d={STAR_PATH} className={styles.outline} />
      {fill !== 'empty' && <path d={STAR_PATH} className={styles.filled} clipPath={fill === 'half' ? `url(#${clipId})` : undefined} />}
    </svg>
  );
}

export interface StarDisplayProps {
  value: number;
  size?: StarSize;
  /** Accessible name (default: "4,5 estrellas"). */
  label?: string;
  className?: string;
}

/** Five stars as one image named by their text equivalent ("4,5 estrellas"). */
export function StarDisplay({ value, size = 'sm', label, className }: StarDisplayProps) {
  return (
    <span role="img" aria-label={label ?? S.ratings.stars(value)} className={cx(styles.display, styles[size], className)}>
      {starFills(value).map((fill, i) => (
        <StarIcon key={i} fill={fill} />
      ))}
    </span>
  );
}

export interface StarInputProps {
  value: number | null;
  onChange: (stars: number) => void;
  /** Enter with a value chosen (and not disabled): the form's save. */
  onEnter?: () => void;
  disabled?: boolean;
}

/**
 * Star slider: ←/↓ and →/↑ step ½ (from empty both give ½), Home ½, End 5, Enter → onEnter.
 * Every handled key is prevented and stopped, so it never scrolls the page under a dialog nor
 * reaches the player's window-level shortcuts; Space is left to the focused button. A pointer down
 * on a star picks its left (½) or right (whole) half; a hovering mouse previews the value.
 */
export function StarInput({ value, onChange, onEnter, disabled = false }: StarInputProps) {
  const [preview, setPreview] = useState<number | null>(null);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowDown':
        next = stepStars(value, -1);
        break;
      case 'ArrowRight':
      case 'ArrowUp':
        next = stepStars(value, 1);
        break;
      case 'Home':
        next = MIN_STARS;
        break;
      case 'End':
        next = MAX_STARS;
        break;
      case 'Enter':
        break;
      default:
        return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (disabled) return;
    if (next !== null) onChange(next);
    else if (value !== null) onEnter?.();
  };

  const pointerValue = (index: number, event: PointerEvent<HTMLElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const fraction = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 1;
    return starsFromPointer(index, fraction);
  };

  const shown = disabled ? value : (preview ?? value);
  return (
    <div
      role="slider"
      tabIndex={disabled ? -1 : 0}
      aria-label={S.ratings.inputLabel}
      aria-valuemin={MIN_STARS}
      aria-valuemax={MAX_STARS}
      aria-valuenow={value ?? undefined}
      aria-valuetext={value === null ? S.ratings.none : S.ratings.stars(value)}
      aria-disabled={disabled || undefined}
      className={cx(styles.input, disabled && styles.disabled)}
      onKeyDown={onKeyDown}
      onPointerLeave={() => setPreview(null)}
    >
      {starFills(shown).map((fill, i) => (
        <span
          key={i}
          data-star={i + 1}
          className={styles.hit}
          onPointerDown={(event) => {
            if (disabled) return;
            setPreview(null);
            onChange(pointerValue(i, event));
          }}
          onPointerMove={(event) => {
            if (!disabled && event.pointerType === 'mouse') setPreview(pointerValue(i, event));
          }}
        >
          <StarIcon fill={fill} />
        </span>
      ))}
    </div>
  );
}
