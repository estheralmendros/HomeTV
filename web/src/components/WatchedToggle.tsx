import { S } from '@/strings.es';

export interface WatchedToggleProps {
  watched: boolean;
  onToggle: (nextWatched: boolean) => void;
  disabled?: boolean;
  /** Icon-only (episode rows): ✓ / ○ with the action as accessible name. */
  compact?: boolean;
}

/** "Marcar como visto" / "Marcar como no visto". */
export function WatchedToggle({ watched, onToggle, disabled = false, compact = false }: WatchedToggleProps) {
  const label = watched ? S.common.markUnwatched : S.common.markWatched;
  return (
    <button
      type="button"
      className="button"
      aria-pressed={watched}
      aria-label={compact ? label : undefined}
      disabled={disabled}
      onClick={() => onToggle(!watched)}
    >
      <span aria-hidden="true">{watched ? '✓' : '○'}</span>
      {!compact && label}
    </button>
  );
}
