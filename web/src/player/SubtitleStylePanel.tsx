// "Formato de los subtítulos": a non-modal panel (playback continues) with five radio groups that
// edit the shell's style draft; every change restyles live (the `::cue` rule, the cue lines and the
// sample line). The panel keeps no copy of the draft: it reads the shell store. Closing it (Cerrar,
// Escape, another menu, unmount) saves once through `onSave` when the draft differs from the style
// it opened with, and always clears the draft, so it never outlives the panel.
// ≥ 600 px it sits at the top right; on phones it is a short sheet above the control bar and the
// sample line (always shown, above the sheet) is the preview.
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type CSSProperties, type KeyboardEvent } from 'react';
import type { SubtitleStyle } from '@/api/types';
import { useMeQuery } from '@/auth/useCurrentProfile';
import { cx } from '@/lib/cx';
import { useElementHeight } from '@/lib/useElementHeight';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { S } from '@/strings.es';
import styles from './Player.module.css';
import { usePlayerShell } from './PlayerRoute';
import {
  COLOR_HEX,
  EDGE_COLOR_HEX,
  SIZE_FACTOR,
  STYLE_ROWS,
  STYLE_VALUES,
  choose,
  edge,
  isDisabled,
  step,
  subtitleStyleOf,
  textShadow,
  type StyleRow,
} from './subtitleStyle';

const T = S.player.subtitleStyle;

const ROW_TITLE: Record<StyleRow, string> = {
  size: T.size,
  color: T.color,
  edge: T.edge,
  edge_color: T.edgeColor,
  position: T.position,
};

const VALUE_LABEL: Record<StyleRow, Record<string, string>> = {
  size: T.sizes,
  color: T.colors,
  edge: T.edges,
  edge_color: T.edgeColors,
  position: T.positions,
};

const SWATCH_HEX: Partial<Record<StyleRow, Record<string, string>>> = {
  color: COLOR_HEX,
  edge_color: EDGE_COLOR_HEX,
};

/** ≥ 600 px: the sample's distance from the bottom per position (prd.md FR-6: 8 / 14 / 20 %). */
const SAMPLE_BOTTOM: Record<SubtitleStyle['position'], string> = { low: '8%', middle: '14%', high: '20%' };
/** < 600 px: the extra raise above the sheet, as a share of the video height. */
const SAMPLE_RAISE: Record<SubtitleStyle['position'], number> = { low: 0, middle: 0.06, high: 0.12 };

const sameStyle = (a: SubtitleStyle, b: SubtitleStyle) =>
  a.size === b.size && a.color === b.color && a.edge === b.edge && a.edge_color === b.edge_color && a.position === b.position;

const HANDLED_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End']);

interface StyleGroupProps {
  row: StyleRow;
  style: SubtitleStyle;
  onChange: (next: SubtitleStyle) => void;
}

function StyleGroup({ row, style, onChange }: StyleGroupProps) {
  const titleId = useId();
  const groupRef = useRef<HTMLDivElement>(null);
  const value = style[row];
  const groupDisabled = row === 'edge_color' && style.edge === 'none';

  // Roving focus follows the checked radio when a key changed it.
  useLayoutEffect(() => {
    const group = groupRef.current;
    if (!group || !group.contains(document.activeElement)) return;
    group.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
  }, [value]);

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!HANDLED_KEYS.has(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    if (groupDisabled) return;
    if (event.key === 'Home' || event.key === 'End') onChange(edge(style, row, event.key === 'Home' ? 'first' : 'last'));
    else onChange(step(style, row, event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1));
  };

  return (
    <div className={styles.styleRow}>
      <p id={titleId} className={styles.styleRowTitle}>
        {ROW_TITLE[row]}
      </p>
      <div
        ref={groupRef}
        role="radiogroup"
        aria-labelledby={titleId}
        aria-disabled={groupDisabled || undefined}
        className={styles.styleOptions}
      >
        {STYLE_VALUES[row].map((option: string) => {
          const checked = option === value;
          const disabled = isDisabled(style, row, option);
          const swatch = SWATCH_HEX[row]?.[option];
          return (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={checked}
              aria-disabled={disabled || undefined}
              disabled={groupDisabled}
              tabIndex={checked && !groupDisabled ? 0 : -1}
              className={styles.styleOption}
              onClick={() => onChange(choose(style, row, option))}
              onKeyDown={onKeyDown}
            >
              {swatch && <span className={styles.styleSwatch} style={{ background: swatch }} aria-hidden="true" />}
              {VALUE_LABEL[row][option]}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** Whether the shown track has an active cue (`activeCues`, followed with `cuechange`). */
function useCueActive(track: TextTrack | null, enabled: boolean): boolean {
  const [active, setActive] = useState(false);
  useEffect(() => {
    if (!track || !enabled) {
      setActive(false);
      return;
    }
    const read = () => setActive((track.activeCues?.length ?? 0) > 0);
    read();
    track.addEventListener('cuechange', read);
    return () => track.removeEventListener('cuechange', read);
  }, [track, enabled]);
  return active;
}

export interface SubtitleStylePanelProps {
  /** [Cerrar] (PlayerPage sets `menu` back to null). */
  onClose: () => void;
  /** Called once on close when the style changed (PlayerPage's useSubtitleStyleSave). */
  onSave: (style: SubtitleStyle) => void;
  /** The shown subtitle TextTrack (null: Off or not loaded). */
  shownTrack: TextTrack | null;
}

export function SubtitleStylePanel({ onClose, onSave, shownTrack }: SubtitleStylePanelProps) {
  const { subtitleStyleDraft: draft } = usePlayerShell();
  const saved = subtitleStyleOf(useMeQuery().data);
  const current = useSyncExternalStore(draft.subscribe, draft.get) ?? saved;
  const phone = useMediaQuery(PHONE_QUERY);
  const titleId = useId();
  const [panelEl, setPanelEl] = useState<HTMLDivElement | null>(null);
  const sheetHeight = useElementHeight(phone ? panelEl : null);
  const cueActive = useCueActive(shownTrack, !phone);

  // Close = save when changed + clear the draft. A no-change mount/unmount (StrictMode) is a no-op.
  const openedWith = useRef(saved);
  const onSaveRef = useRef(onSave);
  onSaveRef.current = onSave;
  useEffect(
    () => () => {
      const last = draft.get();
      if (last && !sameStyle(last, openedWith.current)) onSaveRef.current(last);
      draft.set(null);
    },
    [draft],
  );

  // Focus: the checked size on open.
  useEffect(() => {
    panelEl?.querySelector<HTMLButtonElement>('[role="radio"][aria-checked="true"]')?.focus();
  }, [panelEl]);

  const sampleStyle: CSSProperties = {
    color: COLOR_HEX[current.color],
    textShadow: textShadow(current),
    fontSize: `calc(var(--video-h, 0px) * 0.05 * ${SIZE_FACTOR[current.size]})`,
    bottom: phone
      ? `calc(var(--style-sheet-h) + 8px + var(--video-h, 0px) * ${SAMPLE_RAISE[current.position]})`
      : SAMPLE_BOTTOM[current.position],
  };
  const showSample = phone || !cueActive;

  return (
    <div className={styles.styleLayer} style={{ '--style-sheet-h': `${sheetHeight}px` } as CSSProperties}>
      {showSample && (
        <p className={styles.styleSample} style={sampleStyle} aria-hidden="true" data-testid="subtitle-style-sample">
          {T.sample}
        </p>
      )}
      <div
        ref={setPanelEl}
        role="dialog"
        aria-labelledby={titleId}
        className={cx(styles.stylePanel, phone && styles.styleSheet)}
        data-layout={phone ? 'sheet' : 'panel'}
      >
        <h2 id={titleId} className={styles.styleTitle}>
          {T.title}
        </h2>
        {STYLE_ROWS.map((row) => (
          <StyleGroup key={row} row={row} style={current} onChange={(next) => draft.set(next)} />
        ))}
        <div className={styles.styleActions}>
          <button type="button" className="button" onClick={onClose}>
            {T.close}
          </button>
        </div>
      </div>
    </div>
  );
}
