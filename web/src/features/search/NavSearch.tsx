// Search input. Enter or the magnifier button → /search?q=… (an empty or blank field does
// nothing); on /search, typing updates q in the URL (replace, debounced 300 ms) so results follow.
// Used in the NavBar and, as variant "page", at the top of the search page (below 960 px, where
// the NavBar input is hidden). A new `resetToken` clears the field (the NavBar bumps it when the
// viewer switches screen).
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { cx } from '@/lib/cx';
import { useDebouncedValue } from '@/lib/useDebouncedValue';
import { S } from '@/strings.es';
import styles from './NavSearch.module.css';

export const SEARCH_PATH = '/search';
const DEBOUNCE_MS = 300;
const MAX_LENGTH = 100;

/** Inline SVG magnifier (decorative: its button or link carries the name). */
export function MagnifierIcon({ size = 24 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" focusable="false">
      <circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="currentColor" strokeWidth="2" />
      <path d="M15.5 15.5L21 21" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export function NavSearch({ variant = 'nav', resetToken = 0 }: { variant?: 'nav' | 'page'; resetToken?: number }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const onSearchPage = pathname === SEARCH_PATH;
  const urlQ = onSearchPage ? (searchParams.get('q') ?? '').trim() : null;

  const [value, setValue] = useState(urlQ ?? '');
  const debounced = useDebouncedValue(value, DEBOUNCE_MS);
  /** The q this input last read from or wrote to the URL (null: not on /search). */
  const synced = useRef<string | null>(urlQ);

  // URL → input when q changes from elsewhere (the other search input, back/forward, arrival).
  useEffect(() => {
    if (urlQ === synced.current) return;
    synced.current = urlQ;
    if (urlQ !== null) setValue((current) => (current.trim() === urlQ ? current : urlQ));
  }, [urlQ]);

  // Screen switch → empty field. The q of a /search URL still refills it on back/forward (effect above).
  useEffect(() => {
    if (resetToken) setValue('');
  }, [resetToken]);

  // Input → URL on /search, once typing settles.
  const writeRef = useRef<(q: string) => void>(() => undefined);
  writeRef.current = (q: string) => {
    if (!onSearchPage || q === urlQ) return;
    synced.current = q;
    setSearchParams(q ? { q } : {}, { replace: true });
  };
  useEffect(() => writeRef.current(debounced.trim()), [debounced]);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const q = value.trim();
    if (!q) return;
    if (onSearchPage) {
      writeRef.current(q);
      return;
    }
    navigate(`${SEARCH_PATH}?q=${encodeURIComponent(q)}`);
  };

  return (
    <form role="search" className={cx(styles.form, variant === 'page' && styles.page)} onSubmit={onSubmit}>
      <input
        type="search"
        className={styles.input}
        value={value}
        maxLength={MAX_LENGTH}
        placeholder={S.nav.searchPlaceholder}
        aria-label={variant === 'page' ? S.search.inputLabel : S.nav.searchLabel}
        onChange={(event) => setValue(event.target.value)}
      />
      <button type="submit" className={styles.submit} aria-label={S.nav.search}>
        <MagnifierIcon size={18} />
      </button>
    </form>
  );
}
