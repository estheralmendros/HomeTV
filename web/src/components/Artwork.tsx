import { useState, type ReactNode } from 'react';
import { artworkUrl } from '@/api/urls';
import type { Image } from '@/api/types';
import { cx } from '@/lib/cx';
import styles from './Artwork.module.css';

export type ArtworkKind = 'poster' | 'still' | 'backdrop' | 'profile';

export interface ArtworkProps {
  image: Image | null;
  /** Shown over the placeholder gradient when `image` is null. */
  title: string;
  kind?: ArtworkKind;
  className?: string;
  /** Use `large` as the 2x srcSet (default true). false = `small` only (small circles, e.g. "Reparto"). */
  largeAt2x?: boolean;
  /** Replaces the title tile for a null or failed image (e.g. a person's initial). */
  placeholder?: ReactNode;
  /** `<img loading>` (default 'lazy'); 'eager' for an above-the-fold image (actor page photo). */
  loading?: 'lazy' | 'eager';
  children?: ReactNode;
}

/**
 * `<img>` (lazy unless `loading="eager"`) from `artworkUrl(image, 'small')` with `large` at 2x (unless `largeAt2x` is
 * false); a `null` image, or one that fails to load (404 `artwork_not_found`, expired token), is
 * `placeholder` or else a tile with the title over a neutral gradient. The image is decorative
 * (the card/link carries the accessible name), so its alt is empty. The frame carries
 * `data-kind={kind}`.
 */
export function Artwork({
  image,
  title,
  kind = 'poster',
  className,
  largeAt2x = true,
  placeholder,
  loading = 'lazy',
  children,
}: ArtworkProps) {
  const src = artworkUrl(image, 'small');
  const large = largeAt2x ? artworkUrl(image, 'large') : null;
  // The src that failed; a different image (or token) gets a new attempt.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  return (
    <span className={cx(styles.frame, styles[kind], className)} data-kind={kind}>
      {src !== null && src !== failedSrc ? (
        <img
          className={styles.image}
          src={src}
          srcSet={large ? `${large} 2x` : undefined}
          loading={loading}
          alt=""
          onError={() => setFailedSrc(src)}
        />
      ) : (
        (placeholder ?? (
          <span className={styles.placeholder} data-testid="artwork-placeholder">
            {title}
          </span>
        ))
      )}
      {children}
    </span>
  );
}
