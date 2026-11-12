// Subtitle and audio menus. The open state lives in PlayerPage, so the
// keyboard shortcuts know when a menu is open (Esc closes it, other keys are ignored).
import { useId, type ReactNode, type RefObject } from 'react';
import type { AudioTrack, SubtitleTrack } from '@/api/types';
import { Spinner } from '@/components/Spinner';
import { S } from '@/strings.es';
import styles from './Player.module.css';
import { SUBTITLES_OFF, audioLabel, hasImageTracks, subtitleLabel, textTracks } from './tracks';

interface MenuShellProps {
  label: string;
  icon: string;
  open: boolean;
  disabled?: boolean;
  onToggle: () => void;
  /** The trigger button (PlayerPage focuses "Subtítulos" when the style panel closes). */
  buttonRef?: RefObject<HTMLButtonElement>;
  children: ReactNode;
}

function MenuShell({ label, icon, open, disabled = false, onToggle, buttonRef, children }: MenuShellProps) {
  const menuId = useId();
  return (
    <div className={styles.menuWrap}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.controlButton}
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={onToggle}
      >
        <span aria-hidden="true">{icon}</span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={label} className={styles.menu}>
          <p className={styles.menuTitle} aria-hidden="true">
            {label}
          </p>
          {children}
        </div>
      )}
    </div>
  );
}

function MenuItem({ checked, onSelect, children }: { checked: boolean; onSelect: () => void; children: ReactNode }) {
  return (
    <button type="button" role="menuitemradio" aria-checked={checked} className={styles.menuItem} onClick={onSelect}>
      {checked && <span aria-hidden="true">✓ </span>}
      {children}
    </button>
  );
}

export interface SubtitleMenuProps {
  /** Every subtitle track of the playback response (image tracks are filtered out). */
  tracks: readonly SubtitleTrack[];
  /** The selected track id or "off". */
  selected: string;
  /** Track ids answered `503 subtitle_pending`. */
  pending: ReadonlySet<string>;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onSelect: (trackId: string) => void;
  /** "Formato de los subtítulos": the menu closes and the style panel opens. */
  onFormat: () => void;
  buttonRef?: RefObject<HTMLButtonElement>;
}

export function SubtitleMenu({ tracks, selected, pending, open, onToggle, onClose, onSelect, onFormat, buttonRef }: SubtitleMenuProps) {
  const text = textTracks(tracks);
  const choose = (id: string) => {
    onSelect(id);
    onClose();
  };
  return (
    <MenuShell label={S.player.subtitles.menu} icon="CC" open={open} onToggle={onToggle} buttonRef={buttonRef}>
      <MenuItem checked={selected === SUBTITLES_OFF} onSelect={() => choose(SUBTITLES_OFF)}>
        {S.player.subtitles.off}
      </MenuItem>
      {text.map((track) => (
        <MenuItem key={track.id} checked={selected === track.id} onSelect={() => choose(track.id)}>
          {pending.has(track.id) && <Spinner label={S.player.subtitles.pending} />}
          {pending.has(track.id) ? `${subtitleLabel(track)} · ${S.player.subtitles.pending}` : subtitleLabel(track)}
        </MenuItem>
      ))}
      {text.length === 0 && <p className={styles.menuNote}>{S.player.subtitles.none}</p>}
      {text.length === 0 && hasImageTracks(tracks) && <p className={styles.menuNote}>{S.player.subtitles.imageNote}</p>}
      <hr className={styles.menuSeparator} />
      <button type="button" role="menuitem" className={styles.menuItem} onClick={onFormat}>
        <span className={styles.menuItemText}>{S.player.subtitleStyle.entry}</span>
        <span aria-hidden="true">›</span>
      </button>
    </MenuShell>
  );
}

export interface AudioMenuProps {
  tracks: readonly AudioTrack[];
  /** The current `audio_stream_index` (✓). */
  current: number | null;
  /** True in states without a SWITCH_AUDIO row. */
  disabled: boolean;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onSelect: (audioStreamIndex: number) => void;
}

/** Shown only with ≥ 2 audio tracks. */
export function AudioMenu({ tracks, current, disabled, open, onToggle, onClose, onSelect }: AudioMenuProps) {
  if (tracks.length < 2) return null;
  return (
    <MenuShell label={S.player.audio.menu} icon="♪" open={open && !disabled} disabled={disabled} onToggle={onToggle}>
      {tracks.map((track) => (
        <MenuItem
          key={track.index}
          checked={track.index === current}
          onSelect={() => {
            onClose();
            // Every item, the checked one too: PlayerPage applies the switch and the re-pick rule.
            onSelect(track.index);
          }}
        >
          {audioLabel(track)}
        </MenuItem>
      ))}
    </MenuShell>
  );
}
