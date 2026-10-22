import { useId } from 'react';
import type { ShowSeason } from '@/api/types';
import { cx } from '@/lib/cx';
import { seasonLabel } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './ShowDetailPage.module.css';

export interface SeasonSelectorProps {
  /** In API order (specials last). */
  seasons: ShowSeason[];
  selected: number;
  onSelect: (seasonNumber: number) => void;
  /** id of the panel the tabs control. */
  panelId: string;
}

/** Tabs on desktop, a <select> below 600 px (CSS picks one); labels from seasonLabel. */
export function SeasonSelector({ seasons, selected, onSelect, panelId }: SeasonSelectorProps) {
  const selectId = useId();
  return (
    <div className={styles.seasonSelector}>
      <div role="tablist" aria-label={S.show.seasons} className={styles.tabs}>
        {seasons.map((season) => {
          const active = season.season_number === selected;
          return (
            <button
              key={season.season_number}
              type="button"
              role="tab"
              id={`${panelId}-tab-${season.season_number}`}
              aria-selected={active}
              aria-controls={panelId}
              tabIndex={active ? 0 : -1}
              className={cx(styles.tab, active && styles.tabActive)}
              onClick={() => onSelect(season.season_number)}
            >
              {seasonLabel(season.season_number, season.name)}
            </button>
          );
        })}
      </div>
      <div className={styles.seasonSelect}>
        <label htmlFor={selectId}>{S.show.season}</label>
        <select id={selectId} className={styles.select} value={selected} onChange={(event) => onSelect(Number(event.target.value))}>
          {seasons.map((season) => (
            <option key={season.season_number} value={season.season_number}>
              {seasonLabel(season.season_number, season.name)}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
