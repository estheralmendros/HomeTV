import type { ReactNode } from 'react';
import type { Image } from '@/api/types';
import { withToken } from '@/api/urls';
import { Artwork } from './Artwork';
import styles from './DetailHero.module.css';

export interface DetailHeroProps {
  title: string;
  poster: Image | null;
  backdrop: Image | null;
  /** "2021 · 2 h 35 min · Ciencia ficción, Aventura · 7,8" */
  meta: string;
  /** Extra lines under the meta line (e.g. "150 sin ver"). */
  extra?: ReactNode;
  tagline?: string | null;
  overview?: string | null;
  /** Action buttons. */
  children?: ReactNode;
}

/** Film/show detail header: darkened `backdrop.large`, poster, title, metadata, text and actions. */
export function DetailHero({ title, poster, backdrop, meta, extra, tagline, overview, children }: DetailHeroProps) {
  return (
    <header className={styles.hero}>
      {backdrop && (
        <div className={styles.backdrop} aria-hidden="true">
          <img src={withToken(backdrop.large)} alt="" />
        </div>
      )}
      <div className={styles.content}>
        <Artwork image={poster} title={title} kind="poster" className={styles.poster} />
        <div className={styles.text}>
          <h1 className={styles.title}>{title}</h1>
          {meta && <p className={styles.meta}>{meta}</p>}
          {extra}
          {tagline && <p className={styles.tagline}>{tagline}</p>}
          {overview && <p className={styles.overview}>{overview}</p>}
          {children && <div className={styles.actions}>{children}</div>}
        </div>
      </div>
    </header>
  );
}
