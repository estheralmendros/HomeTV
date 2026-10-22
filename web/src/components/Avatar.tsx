import { useQuery } from '@tanstack/react-query';
import { infoQuery } from '@/api/queries';
import type { Avatar as AvatarDef } from '@/api/types';
import { cx } from '@/lib/cx';
import styles from './Avatar.module.css';

export type AvatarSize = 'sm' | 'md' | 'lg';

const FALLBACK_COLOR = '#4A90E2';

export function avatarColor(avatars: AvatarDef[] | undefined, avatarId: string): string {
  return avatars?.find((a) => a.id === avatarId)?.color ?? FALLBACK_COLOR;
}

/** First letter of the name, uppercased (es-ES). */
export function initialOf(name: string): string {
  return (name.trim().charAt(0) || '?').toLocaleUpperCase('es-ES');
}

/** Decorative color circle with the name's initial (the name is shown next to it). */
export function Avatar({ name, color, size = 'md' }: { name: string; color: string; size?: AvatarSize }) {
  return (
    <span className={cx(styles.avatar, styles[size])} style={{ background: color }} aria-hidden="true">
      {initialOf(name)}
    </span>
  );
}

/** Avatar of a profile, colored from `info.avatars`. */
export function ProfileAvatar({ name, avatarId, size }: { name: string; avatarId: string; size?: AvatarSize }) {
  const { data: info } = useQuery(infoQuery());
  return <Avatar name={name} color={avatarColor(info?.avatars, avatarId)} size={size} />;
}
