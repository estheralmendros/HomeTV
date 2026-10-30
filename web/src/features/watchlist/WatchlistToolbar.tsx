// "Mi lista" toolbar (watchlist prd2 FR-W10.2): type filter as a three-button group (aria-pressed)
// and the sort as a labelled <select>. Fixed directions, so no order toggle. Same controls on phones.
import { useId } from 'react';
import type { WatchlistSort, WatchlistType } from '@/api/types';
import toolbarStyles from '@/components/Toolbar.module.css';
import { S } from '@/strings.es';
import styles from './WatchlistPage.module.css';
import type { useWatchlistParams } from './useWatchlistParams';

type Watchlist = ReturnType<typeof useWatchlistParams>;

const TYPE_OPTIONS: ReadonlyArray<{ value: WatchlistType; label: string }> = [
  { value: 'all', label: S.watchlist.typeAll },
  { value: 'films', label: S.watchlist.typeFilms },
  { value: 'shows', label: S.watchlist.typeShows },
];

const SORT_OPTIONS: ReadonlyArray<{ value: WatchlistSort; label: string }> = [
  { value: 'added', label: S.watchlist.sortAdded },
  { value: 'title', label: S.watchlist.sortTitle },
  { value: 'year', label: S.watchlist.sortYear },
];

export function WatchlistToolbar({ watchlist }: { watchlist: Watchlist }) {
  const { params, setType, setSort } = watchlist;
  const typeLabelId = useId();
  const sortId = useId();
  return (
    <div className={toolbarStyles.toolbar} role="group" aria-label={S.watchlist.toolbar}>
      <div className={toolbarStyles.field}>
        <span id={typeLabelId}>{S.watchlist.typeLabel}</span>
        <div className={styles.types} role="group" aria-labelledby={typeLabelId}>
          {TYPE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`button ${styles.type}`}
              aria-pressed={params.type === option.value}
              onClick={() => setType(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      <label className={toolbarStyles.field} htmlFor={sortId}>
        {S.watchlist.sort}
        <select
          id={sortId}
          className={toolbarStyles.select}
          value={params.sort}
          onChange={(event) => setSort(event.target.value as WatchlistSort)}
        >
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
