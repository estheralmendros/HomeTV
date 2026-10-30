import { S } from '@/strings.es';

export interface WatchlistToggleProps {
  inList: boolean;
  onToggle: (nextInList: boolean) => void;
  disabled?: boolean;
}

/** "Mi lista" / "En Mi lista"; the accessible name is the action ("Añadir a…" / "Quitar de…"). */
export function WatchlistToggle({ inList, onToggle, disabled = false }: WatchlistToggleProps) {
  return (
    <button
      type="button"
      className="button"
      aria-pressed={inList}
      aria-label={inList ? S.watchlist.remove : S.watchlist.add}
      disabled={disabled}
      onClick={() => onToggle(!inList)}
    >
      <span aria-hidden="true">{inList ? '✓' : '+'}</span>
      {inList ? S.watchlist.inList : S.watchlist.toggle}
    </button>
  );
}
