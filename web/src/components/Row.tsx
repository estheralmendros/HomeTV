import { useId, useRef, type ReactNode } from 'react';
import { S } from '@/strings.es';
import styles from './Row.module.css';

/** Horizontal row with scroll-snap; ‹ › buttons (pointer devices) scroll by one viewport width. */
export function Row({ title, children }: { title: string; children: ReactNode }) {
  const headingId = useId();
  const trackRef = useRef<HTMLDivElement>(null);
  const scroll = (direction: -1 | 1) => {
    const track = trackRef.current;
    if (!track) return;
    const left = direction * track.clientWidth;
    if (typeof track.scrollBy === 'function') track.scrollBy({ left, behavior: 'smooth' });
    else track.scrollLeft += left;
  };
  return (
    <section className={styles.row} aria-labelledby={headingId}>
      <div className={styles.header}>
        <h2 id={headingId} className={styles.heading}>
          {title}
        </h2>
        <div className={styles.controls}>
          <button type="button" className={styles.arrow} aria-label={S.row.previous} onClick={() => scroll(-1)}>
            ‹
          </button>
          <button type="button" className={styles.arrow} aria-label={S.row.next} onClick={() => scroll(1)}>
            ›
          </button>
        </div>
      </div>
      <div ref={trackRef} className={styles.track} data-testid="row-track">
        {children}
      </div>
    </section>
  );
}
