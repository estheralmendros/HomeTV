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
  children?: ReactNode;
}

/**
 * Lazy `<img>` from `artworkUrl(image, 'small')` with `large` at 2x; a `null` image, or one that
 * fails to load (404 `artwork_not_found`, expired token), is a placeholder tile with the title
 * over a neutral gradient. The image is decorative (the card/link carries the accessible name),
 * so its alt is empty.
 */
export function Artwork({ image, title, kind = 'poster', className, children }: ArtworkProps) {
  const src = artworkUrl(image, 'small');
  const large = artworkUrl(image, 'large');
  // The src that failed; a different image (or token) gets a new attempt.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  return (
    <span className={cx(styles.frame, styles[kind], className)}>
      {src !== null && src !== failedSrc ? (
        <img
          className={styles.image}
          src={src}
          srcSet={large ? `${large} 2x` : undefined}
          loading="lazy"
          alt=""
          onError={() => setFailedSrc(src)}
        />
      ) : (
        <span className={styles.placeholder} data-testid="artwork-placeholder">
          {title}
        </span>
      )}
      {children}
    </span>
  );
}
