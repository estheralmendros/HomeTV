import { Link } from 'react-router-dom';
import type { CastMember } from '@/api/types';
import { S } from '@/strings.es';
import { Artwork } from './Artwork';
import { initialOf } from './Avatar';
import styles from './CastRow.module.css';
import { Row } from './Row';

/**
 * "Reparto": each member links to the actor page (/people/{person_id}). The 96 px photo circle
 * loads `small` only; without a photo (or when it fails) it shows the initial.
 */
export function CastRow({ cast }: { cast: CastMember[] }) {
  if (cast.length === 0) return null;
  return (
    <Row title={S.film.cast}>
      {cast.map((member, index) => (
        <Link
          key={`${member.person_id}:${index}`}
          to={`/people/${member.person_id}`}
          className={styles.member}
          aria-label={member.character ? S.film.castMemberLabel(member.name, member.character) : member.name}
        >
          <Artwork
            image={member.profile_image}
            title={member.name}
            kind="profile"
            className={styles.photo}
            largeAt2x={false}
            placeholder={
              <span className={styles.initials} aria-hidden="true">
                {initialOf(member.name)}
              </span>
            }
          />
          <span className={styles.name}>{member.name}</span>
          {member.character && <span className={styles.character}>{member.character}</span>}
        </Link>
      ))}
    </Row>
  );
}
