import { useId, useRef, useState } from 'react';
import { cx } from '@/lib/cx';
import { useOverflows } from '@/lib/useOverflows';
import { S } from '@/strings.es';
import styles from './Biography.module.css';

/**
 * Biography clamped to 4 lines. "Leer más" / "Leer menos" appears only when the clamped text is
 * cut off (or while expanded: useOverflows keeps its last value while inactive).
 */
export function Biography({ text, lang }: { text: string; lang?: string }) {
  const textId = useId();
  const ref = useRef<HTMLParagraphElement>(null);
  const [expanded, setExpanded] = useState(false);
  const overflows = useOverflows(ref, !expanded, text);
  return (
    <>
      <p ref={ref} id={textId} lang={lang} className={cx(styles.bio, !expanded && styles.clamped)}>
        {text}
      </p>
      {(overflows || expanded) && (
        <button
          type="button"
          className={styles.toggle}
          aria-expanded={expanded}
          aria-controls={textId}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? S.person.readLess : S.person.readMore}
        </button>
      )}
    </>
  );
}
