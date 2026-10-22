import type { Avatar as AvatarDef } from '@/api/types';
import { Avatar } from '@/components/Avatar';
import { S } from '@/strings.es';
import styles from './BootstrapPage.module.css';

export interface AvatarPickerProps {
  avatars: AvatarDef[];
  /** Initial shown in each circle (the name being typed). */
  name: string;
  value: string | null;
  onChange: (avatarId: string) => void;
  legend: string;
  errorId?: string;
}

/** Radio grid of `info.avatars` (color circle + the name's initial). */
export function AvatarPicker({ avatars, name, value, onChange, legend, errorId }: AvatarPickerProps) {
  return (
    <fieldset className={styles.avatars} aria-describedby={errorId}>
      <legend className="visually-hidden">{legend}</legend>
      {avatars.map((avatar, i) => (
        <label key={avatar.id} className={styles.avatarOption}>
          <input
            type="radio"
            name="avatar_id"
            className="visually-hidden"
            value={avatar.id}
            checked={value === avatar.id}
            aria-label={S.setup.avatarOption(i + 1)}
            onChange={() => onChange(avatar.id)}
          />
          <Avatar name={name || '?'} color={avatar.color} size="md" />
        </label>
      ))}
    </fieldset>
  );
}
