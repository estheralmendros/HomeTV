import type { ReactNode } from 'react';
import { withToken } from '@/api/urls';
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
 * Lazy `<img>` from `withToken(image.small)` with `large` at 2x; a `null` image is a placeholder
 * tile with the title over a neutral gradient. The image is decorative (the card/link carries
 * the accessible name), so its alt is empty.
 */
export function Artwork({ image, title, kind = 'poster', className, children }: ArtworkProps) {
  return (
    <span className={cx(styles.frame, styles[kind], className)}>
      {image ? (
        <img
          className={styles.image}
          src={withToken(image.small)}
          srcSet={`${withToken(image.large)} 2x`}
          loading="lazy"
          alt=""
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
