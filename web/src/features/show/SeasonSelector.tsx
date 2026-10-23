import { useId, useRef, type KeyboardEvent } from 'react';
import type { ShowSeason } from '@/api/types';
import { cx } from '@/lib/cx';
import { seasonLabel } from '@/lib/format';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
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

/** Tabs on desktop, a <select> on phones (PHONE_QUERY); labels from seasonLabel. */
export function SeasonSelector(props: SeasonSelectorProps) {
  const phone = useMediaQuery(PHONE_QUERY);
  return <div className={styles.seasonSelector}>{phone ? <SeasonSelect {...props} /> : <SeasonTabs {...props} />}</div>;
}

/**
 * WAI-ARIA tabs with a roving tabindex: only the selected tab is in the tab order;
 * ArrowLeft/ArrowRight (wrapping), Home and End move focus and select (automatic activation).
 */
function SeasonTabs({ seasons, selected, onSelect, panelId }: SeasonSelectorProps) {
  const tabs = useRef(new Map<number, HTMLButtonElement>());

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = seasons.findIndex((s) => s.season_number === selected);
    const last = seasons.length - 1;
    let target: number;
    switch (event.key) {
      case 'ArrowRight':
        target = current >= last ? 0 : current + 1;
        break;
      case 'ArrowLeft':
        target = current <= 0 ? last : current - 1;
        break;
      case 'Home':
        target = 0;
        break;
      case 'End':
        target = last;
        break;
      default:
        return;
    }
    event.preventDefault();
    const season = seasons[target];
    if (!season) return;
    tabs.current.get(season.season_number)?.focus();
    if (season.season_number !== selected) onSelect(season.season_number);
  };

  return (
    <div role="tablist" aria-label={S.show.seasons} className={styles.tabs} onKeyDown={onKeyDown}>
      {seasons.map((season) => {
        const active = season.season_number === selected;
        return (
          <button
            key={season.season_number}
            ref={(el) => {
              if (el) tabs.current.set(season.season_number, el);
              else tabs.current.delete(season.season_number);
            }}
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
  );
}

function SeasonSelect({ seasons, selected, onSelect }: SeasonSelectorProps) {
  const selectId = useId();
  return (
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
  );
}
