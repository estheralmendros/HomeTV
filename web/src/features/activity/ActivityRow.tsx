// One /activity row: a link to the title with avatar, sentence + stars,
// relative time and a small poster (a compact title tile without one). The link's name is the full
// text equivalent.
import { Link } from 'react-router-dom';
import type { ActivityEntry } from '@/api/types';
import { Artwork } from '@/components/Artwork';
import { ProfileAvatar } from '@/components/Avatar';
import { StarDisplay } from '@/components/StarRating';
import { formatActivityTime } from '@/lib/format';
import { S } from '@/strings.es';
import styles from './ActivityPage.module.css';

export function ActivityRow({ entry, own }: { entry: ActivityEntry; own: boolean }) {
  const { profile, item, stars, rated_at: ratedAt } = entry;
  const time = formatActivityTime(ratedAt);
  const starsText = S.ratings.stars(stars);
  const label = own ? S.activity.ownRow(item.title, starsText, time) : S.activity.row(profile.name, item.title, starsText, time);
  return (
    <li>
      <Link to={entry.kind === 'film' ? `/films/${item.id}` : `/shows/${item.id}`} className={styles.row} aria-label={label}>
        <ProfileAvatar name={profile.name} avatarId={profile.avatar_id} size="sm" />
        <span className={styles.text}>
          <span className={styles.sentence}>
            {own ? (
              <>
                {S.activity.ownPrefix} <strong>{item.title}</strong> {S.activity.with}{' '}
              </>
            ) : (
              <>
                <strong>{profile.name}</strong> {S.activity.ratedPrefix} <strong>{item.title}</strong> {S.activity.with}{' '}
              </>
            )}
            <StarDisplay value={stars} className={styles.stars} />
          </span>
          <time className={styles.time} dateTime={ratedAt}>
            {time}
          </time>
        </span>
        <Artwork
          image={item.poster}
          title={item.title}
          kind="poster"
          className={styles.poster}
          largeAt2x={false}
          placeholder={
            // The default title tile (1rem bold, 12 px padding) clips words in 46 px.
            <span className={styles.posterTitle} data-testid="activity-poster-title">
              <span className={styles.posterTitleText}>{item.title}</span>
            </span>
          }
        />
      </Link>
    </li>
  );
}
