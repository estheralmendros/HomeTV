import { cx } from '@/lib/cx';
import { starsFormat } from '@/lib/intl';
import styles from './Card.module.css';
import { StarIcon } from './StarRating';

/** The viewer's rating on a card's artwork: bottom-left "★ 4,5", raised above a progress bar.
 *  Decorative: the card's link name carries `S.cards.withRating`. */
export function RatingBadge({ stars, aboveProgress }: { stars: number; aboveProgress: boolean }) {
  return (
    <span className={cx(styles.rating, aboveProgress && styles.ratingAboveProgress)} data-testid="rating-badge" aria-hidden="true">
      <StarIcon fill="full" />
      {starsFormat.format(stars)}
    </span>
  );
}
