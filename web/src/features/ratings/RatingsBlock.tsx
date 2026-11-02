// "Tu puntuación" / "Media de la familia" / the other profiles, under the
// detail's meta line. Reads only the cached detail: useRatingWrite patches it optimistically. A
// write in flight for the title (any mount) disables [Puntuar] / [Cambiar] with `aria-disabled`
// (not `disabled`): the Modal returns focus to this button on close, often while the write is still
// pending ([Quitar puntuación] closes at once), and a disabled button cannot take it.
import { useIsMutating } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { mutationKeys } from '@/api/queryKeys';
import type { HouseholdAverage, Image, OtherRating, RatingKind } from '@/api/types';
import { ProfileAvatar } from '@/components/Avatar';
import { StarDisplay } from '@/components/StarRating';
import { roundToHalf } from '@/lib/stars';
import { S } from '@/strings.es';
import { RatingDialog } from './RatingDialog';
import styles from './Ratings.module.css';

/** Other profiles shown before the "y N más" toggle. */
export const VISIBLE_OTHERS = 3;

export interface RatingsBlockProps {
  kind: RatingKind;
  id: number;
  title: string;
  poster: Image | null;
  myRating: number | null;
  /** Newest first (server order). */
  ratings: OtherRating[];
  average: HouseholdAverage | null;
}

export function RatingsBlock({ kind, id, title, poster, myRating, ratings, average }: RatingsBlockProps) {
  const pending = useIsMutating({ mutationKey: mutationKeys.ratingWrite(kind, id) }) > 0;
  const [dialogOpen, setDialogOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const mineId = useId();
  const averageId = useId();
  const shown = expanded ? ratings : ratings.slice(0, VISIBLE_OTHERS);
  const hidden = ratings.length - VISIBLE_OTHERS;

  return (
    <div className={styles.block}>
      <div className={styles.row} role="group" aria-labelledby={mineId}>
        <span id={mineId} className={styles.label}>
          {S.ratings.mine}
        </span>
        {myRating !== null && <StarDisplay value={myRating} size="md" />}
        <button
          type="button"
          className={styles.action}
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) setDialogOpen(true);
          }}
        >
          {myRating !== null ? S.ratings.change : S.ratings.rate}
        </button>
      </div>
      {average && (
        <div className={styles.row} role="group" aria-labelledby={averageId}>
          <span id={averageId} className={styles.label}>
            {S.ratings.averageHeading}
          </span>
          <StarDisplay value={roundToHalf(average.stars)} label={S.ratings.averageLabel(average.stars, average.count)} />
          <span className={styles.averageText} aria-hidden="true">
            {S.ratings.average(average.stars, average.count)}
          </span>
        </div>
      )}
      {ratings.length > 0 && (
        <div className={styles.others}>
          <ul className={styles.otherList} aria-label={S.ratings.others}>
            {shown.map((rating) => (
              <li key={rating.profile.id} className={styles.other}>
                <ProfileAvatar name={rating.profile.name} avatarId={rating.profile.avatar_id} size="sm" />
                <span className={styles.otherName}>{rating.profile.name}</span>
                <StarDisplay value={rating.stars} />
              </li>
            ))}
          </ul>
          {hidden > 0 && (
            <button type="button" className={styles.action} aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}>
              {expanded ? S.ratings.less : S.ratings.more(hidden)}
            </button>
          )}
        </div>
      )}
      {dialogOpen && (
        <RatingDialog
          mode="manual"
          target={{ kind, id, title, poster }}
          initial={myRating}
          onClose={() => setDialogOpen(false)}
        />
      )}
    </div>
  );
}
