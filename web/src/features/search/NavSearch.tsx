// Search input. Enter → /search?q=…; on /search, typing updates q in the URL
// (replace, debounced 300 ms) so results follow. Used in the NavBar and, as variant "page",
// at the top of the search page (phones, where the NavBar input is hidden).
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { cx } from '@/lib/cx';
import { useDebouncedValue } from '@/lib/useDebouncedValue';
import { S } from '@/strings.es';
import styles from './NavSearch.module.css';

export const SEARCH_PATH = '/search';
const DEBOUNCE_MS = 300;
const MAX_LENGTH = 100;

export function NavSearch({ variant = 'nav' }: { variant?: 'nav' | 'page' }) {
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
    if (onSearchPage) {
      writeRef.current(q);
      return;
    }
    navigate(q ? `${SEARCH_PATH}?q=${encodeURIComponent(q)}` : SEARCH_PATH);
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
    </form>
  );
}
