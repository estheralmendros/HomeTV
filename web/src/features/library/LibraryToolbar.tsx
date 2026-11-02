// Library toolbar: title filter (debounced 300 ms), sort + order toggle,
// genre, year range and watched state. Below 600 px (PHONE_QUERY) only the title filter and a "Filtros" button
// render; the button opens a bottom sheet with the other controls and "Borrar" / "Aplicar".
import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { genresQuery } from '@/api/queries';
import type { GenreCount, LibraryType, SortField, SortOrder } from '@/api/types';
import { Modal } from '@/components/Modal';
import toolbarStyles from '@/components/Toolbar.module.css';
import { cx } from '@/lib/cx';
import { useDebouncedValue } from '@/lib/useDebouncedValue';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { S } from '@/strings.es';
import styles from './Library.module.css';
import {
  DEFAULT_ORDER,
  MAX_QUERY_LENGTH,
  MAX_YEAR,
  MIN_YEAR,
  parseYear,
  type useLibraryParams,
} from './useLibraryParams';

type Library = ReturnType<typeof useLibraryParams>;

const SEARCH_DEBOUNCE_MS = 300;

const SORT_OPTIONS: ReadonlyArray<{ value: SortField; label: string }> = [
  { value: 'title', label: S.library.sortTitle },
  { value: 'year', label: S.library.sortYear },
  { value: 'added', label: S.library.sortAdded },
];

const WATCHED_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: '', label: S.library.watchedAll },
  { value: 'true', label: S.library.watchedOnly },
  { value: 'false', label: S.library.unwatchedOnly },
];

const flip = (order: SortOrder): SortOrder => (order === 'asc' ? 'desc' : 'asc');

export function LibraryToolbar({ type, library }: { type: LibraryType; library: Library }) {
  const { params, update, setSort, toggleOrder, clearFilters, hasFilters, yearRangeInvalid } = library;
  const genres = useQuery(genresQuery(type)).data?.items;
  const [sheetOpen, setSheetOpen] = useState(false);
  // Rendered conditionally, not hidden with CSS: the global `.button` rule could otherwise win the
  // cascade and show "Filtros" next to the inline controls on wide screens.
  const phone = useMediaQuery(PHONE_QUERY);

  return (
    <div className={toolbarStyles.toolbar} role="group" aria-label={S.library.toolbar}>
      <TitleFilter value={params.q} onCommit={(q) => update({ q })} />
      {phone ? (
        <button type="button" className="button" onClick={() => setSheetOpen(true)}>
          {S.library.filters}
        </button>
      ) : (
        <div className={styles.fields}>
          <SortFields sort={params.sort} order={params.order} onSort={setSort} onToggleOrder={toggleOrder} />
          <GenreField genres={genres} value={params.genre} onChange={(genre) => update({ genre })} />
          <YearRange
            from={<CommittedYearInput label={S.library.yearFrom} value={params.yearFrom} onCommit={(yearFrom) => update({ yearFrom })} />}
            to={<CommittedYearInput label={S.library.yearTo} value={params.yearTo} onCommit={(yearTo) => update({ yearTo })} />}
          />
          <WatchedField value={params.watched} onChange={(watched) => update({ watched })} />
          {hasFilters && (
            <button type="button" className="button" onClick={clearFilters}>
              {S.library.clearFilters}
            </button>
          )}
        </div>
      )}
      {yearRangeInvalid && (
        <p className={styles.error} role="alert">
          {S.library.yearRangeError}
        </p>
      )}
      {phone && sheetOpen && <FilterSheet library={library} genres={genres} onClose={() => setSheetOpen(false)} />}
    </div>
  );
}

/** Local input state; the trimmed value reaches the URL 300 ms after the last keystroke. */
function TitleFilter({ value, onCommit }: { value: string; onCommit: (q: string) => void }) {
  const [text, setText] = useState(value);
  const debounced = useDebouncedValue(text, SEARCH_DEBOUNCE_MS);
  const onCommitRef = useRef(onCommit);
  onCommitRef.current = onCommit;

  // URL → input when it changes from elsewhere (Borrar filtros, back/forward).
  useEffect(() => {
    setText((current) => (current.trim().slice(0, MAX_QUERY_LENGTH) === value ? current : value));
  }, [value]);

  useEffect(() => {
    const q = debounced.trim().slice(0, MAX_QUERY_LENGTH);
    if (q !== value) onCommitRef.current(q);
    // Only a settled keystroke commits (deps: debounced only); a URL change must not write back.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: `value` is read, not a trigger
  }, [debounced]);

  return (
    <input
      type="search"
      className={cx(toolbarStyles.input, styles.search)}
      value={text}
      maxLength={MAX_QUERY_LENGTH}
      placeholder={S.library.filterPlaceholder}
      aria-label={S.library.filterLabel}
      onChange={(event) => setText(event.target.value)}
    />
  );
}

function SortFields({
  sort,
  order,
  onSort,
  onToggleOrder,
}: {
  sort: SortField;
  order: SortOrder;
  onSort: (sort: SortField) => void;
  onToggleOrder: () => void;
}) {
  const id = useId();
  return (
    <>
      <label className={toolbarStyles.field} htmlFor={id}>
        {S.library.sort}
        <select
          id={id}
          className={toolbarStyles.select}
          value={sort}
          onChange={(event) => onSort(event.target.value as SortField)}
        >
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className="button" onClick={onToggleOrder}>
        <span aria-hidden="true">{order === 'asc' ? '↑' : '↓'}</span>
        {order === 'asc' ? S.library.asc : S.library.desc}
      </button>
    </>
  );
}

function GenreField({
  genres,
  value,
  onChange,
}: {
  genres: GenreCount[] | undefined;
  value: number | null;
  onChange: (genre: number | null) => void;
}) {
  const id = useId();
  // Until the list arrives, keep a selected genre representable.
  const known = genres?.some((g) => g.id === value) ?? false;
  return (
    <label className={toolbarStyles.field} htmlFor={id}>
      {S.library.genre}
      <select
        id={id}
        className={toolbarStyles.select}
        value={value === null || !known ? '' : String(value)}
        onChange={(event) => onChange(event.target.value ? Number(event.target.value) : null)}
      >
        <option value="">{S.library.allGenres}</option>
        {genres?.map((genre) => (
          <option key={genre.id} value={genre.id}>
            {S.library.genreOption(genre.name, genre.count)}
          </option>
        ))}
      </select>
    </label>
  );
}

function WatchedField({ value, onChange }: { value: boolean | null; onChange: (watched: boolean | null) => void }) {
  const id = useId();
  return (
    <label className={toolbarStyles.field} htmlFor={id}>
      {S.library.watched}
      <select
        id={id}
        className={toolbarStyles.select}
        value={value === null ? '' : String(value)}
        onChange={(event) => onChange(event.target.value === '' ? null : event.target.value === 'true')}
      >
        {WATCHED_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

function YearRange({ from, to }: { from: ReactNode; to: ReactNode }) {
  return (
    <fieldset className={styles.yearRange}>
      <legend>{S.library.year}</legend>
      {from}
      <span aria-hidden="true">–</span>
      {to}
    </fieldset>
  );
}

function YearInput({
  label,
  value,
  onChange,
  onBlur,
}: {
  label: string;
  value: string;
  onChange: (raw: string) => void;
  onBlur?: () => void;
}) {
  const id = useId();
  return (
    <label className={toolbarStyles.field} htmlFor={id}>
      {label}
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={MIN_YEAR}
        max={MAX_YEAR}
        className={cx(toolbarStyles.input, styles.year)}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
      />
    </label>
  );
}

/** A year input bound to the URL: commits a complete valid year (or empty); partial input stays local. */
function CommittedYearInput({ label, value, onCommit }: { label: string; value: number | null; onCommit: (year: number | null) => void }) {
  const [text, setText] = useState(value === null ? '' : String(value));
  useEffect(() => {
    setText((current) => (parseYear(current) === value ? current : value === null ? '' : String(value)));
  }, [value]);
  return (
    <YearInput
      label={label}
      value={text}
      onChange={(raw) => {
        setText(raw);
        if (raw.trim() === '') onCommit(null);
        else {
          const year = parseYear(raw);
          if (year !== null) onCommit(year);
        }
      }}
      onBlur={() => setText(value === null ? '' : String(value))}
    />
  );
}

interface Draft {
  sort: SortField;
  order: SortOrder;
  genre: number | null;
  yearFrom: string;
  yearTo: string;
  watched: boolean | null;
}

/** Mobile bottom sheet: edits a draft, applied to the URL on "Aplicar". */
function FilterSheet({ library, genres, onClose }: { library: Library; genres: GenreCount[] | undefined; onClose: () => void }) {
  const { params, update, clearFilters } = library;
  const [draft, setDraft] = useState<Draft>({
    sort: params.sort,
    order: params.order,
    genre: params.genre,
    yearFrom: params.yearFrom === null ? '' : String(params.yearFrom),
    yearTo: params.yearTo === null ? '' : String(params.yearTo),
    watched: params.watched,
  });
  const yearFrom = parseYear(draft.yearFrom);
  const yearTo = parseYear(draft.yearTo);
  const invalid = yearFrom !== null && yearTo !== null && yearFrom > yearTo;
  const patch = (changes: Partial<Draft>) => setDraft((d) => ({ ...d, ...changes }));

  return (
    <Modal title={S.library.filters} onClose={onClose} className={styles.sheet}>
      <div className={styles.sheetFields}>
        <div className={styles.fields}>
          <SortFields
            sort={draft.sort}
            order={draft.order}
            onSort={(sort) => patch({ sort, order: DEFAULT_ORDER[sort] })}
            onToggleOrder={() => patch({ order: flip(draft.order) })}
          />
        </div>
        <GenreField genres={genres} value={draft.genre} onChange={(genre) => patch({ genre })} />
        <YearRange
          from={<YearInput label={S.library.yearFrom} value={draft.yearFrom} onChange={(yearFrom) => patch({ yearFrom })} />}
          to={<YearInput label={S.library.yearTo} value={draft.yearTo} onChange={(yearTo) => patch({ yearTo })} />}
        />
        <WatchedField value={draft.watched} onChange={(watched) => patch({ watched })} />
        {invalid && (
          <p className={styles.error} role="alert">
            {S.library.yearRangeError}
          </p>
        )}
      </div>
      <div className={styles.sheetActions}>
        <button
          type="button"
          className="button"
          onClick={() => {
            clearFilters();
            onClose();
          }}
        >
          {S.library.clear}
        </button>
        <button
          type="button"
          className="button button-primary"
          disabled={invalid}
          onClick={() => {
            update({ sort: draft.sort, order: draft.order, genre: draft.genre, yearFrom, yearTo, watched: draft.watched });
            onClose();
          }}
        >
          {S.library.apply}
        </button>
      </div>
    </Modal>
  );
}
