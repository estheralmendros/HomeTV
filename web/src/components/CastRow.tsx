import type { CastMember } from '@/api/types';
import { S } from '@/strings.es';
import { Artwork } from './Artwork';
import { Avatar } from './Avatar';
import styles from './CastRow.module.css';
import { Row } from './Row';

/** "Reparto": profile images (or initials) with name and character. */
export function CastRow({ cast }: { cast: CastMember[] }) {
  if (cast.length === 0) return null;
  return (
    <Row title={S.film.cast}>
      {cast.map((member) => (
        <div key={member.person_id} className={styles.member}>
          {member.profile_image ? (
            <Artwork image={member.profile_image} title={member.name} kind="profile" className={styles.photo} />
          ) : (
            <Avatar name={member.name} color="var(--color-surface-2)" size="lg" />
          )}
          <span className={styles.name}>{member.name}</span>
          {member.character && <span className={styles.character}>{member.character}</span>}
        </div>
      ))}
    </Row>
  );
}
