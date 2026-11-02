// Actor page: photo, name, life span, place of birth, biography
// and the person's titles in this catalog ("En tu catálogo"). Reached from "Reparto" links; no
// back button (browser Back, like detail pages).
import { useQuery } from '@tanstack/react-query';
import { useId, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, errorMessage } from '@/api/errors';
import { personQuery } from '@/api/queries';
import type { FilmSummary, PersonDetail, ShowSummary } from '@/api/types';
import { Artwork } from '@/components/Artwork';
import { initialOf } from '@/components/Avatar';
import { ErrorState } from '@/components/ErrorState';
import { NotAvailable } from '@/components/NotAvailable';
import { SkeletonDetail } from '@/components/Skeleton';
import { PosterGrid } from '@/features/library/PosterGrid';
import { parseRouteId } from '@/lib/routeId';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import { appearanceSubtitle } from './appearanceSubtitle';
import { Biography } from './Biography';
import { lifeSpanText, todayIso } from './lifeSpan';
import styles from './PersonPage.module.css';

export function PersonPage() {
  const id = parseRouteId(useParams().id);
  const person = useQuery({ ...personQuery(id ?? 0), enabled: id !== null });
  useDocumentTitle(person.data ? person.data.name : null);

  if (id === null || (person.error instanceof ApiError && person.error.status === 404)) {
    return <NotAvailable message={S.person.notFound} backTo="/" backLabel={S.common.goHome} />;
  }
  if (person.isPending) return <div className="page"><SkeletonDetail /></div>;
  // Only when nothing was ever loaded: a failed background refetch (offline, networkMode 'always')
  // keeps the cached person on screen, like the film and show pages.
  if (person.isError && person.data === undefined) {
    return (
      <div className="page">
        <ErrorState message={errorMessage(person.error)} onRetry={() => void person.refetch()} />
      </div>
    );
  }
  return <PersonView key={person.data.person_id} person={person.data} />;
}

/** Primary subtag of a TMDB language tag ("en-US" → "en"), undefined when unknown. */
function primaryLanguage(tag: string | null): string | undefined {
  return tag?.split('-')[0] || undefined;
}

function PersonView({ person }: { person: PersonDetail }) {
  const bioId = useId();
  const catalogId = useId();
  const isPhone = useMediaQuery(PHONE_QUERY);
  const lifeSpan = lifeSpanText(person.birthday, person.deathday, todayIso());
  const subtitleFor = useMemo(() => {
    const byItem = new Map(person.appearances.map((a) => [`${a.kind}-${a.item.id}`, appearanceSubtitle(a)]));
    return (item: FilmSummary | ShowSummary) => byItem.get(`${item.type}-${item.id}`) ?? null;
  }, [person.appearances]);

  // Desktop: in the text column under the place of birth (as on the TV); phones: below the header,
  // where the column next to the photo is too narrow.
  const biography = person.biography !== null && (
    <section className={isPhone ? styles.section : styles.bio} aria-labelledby={bioId}>
      <h2 id={bioId} className={styles.heading}>
        {S.person.biography}
      </h2>
      <Biography text={person.biography} lang={primaryLanguage(person.biography_language)} />
    </section>
  );

  return (
    <div className="page">
      <header className={styles.header}>
        <Artwork
          image={person.profile_image}
          title={person.name}
          kind="poster"
          className={styles.photo}
          loading="eager"
          placeholder={
            <span className={styles.photoInitials} aria-hidden="true">
              {initialOf(person.name)}
            </span>
          }
        />
        <div className={styles.text}>
          <h1 className={styles.name}>{person.name}</h1>
          {lifeSpan !== null && <p className={styles.lifeSpan}>{lifeSpan}</p>}
          {person.place_of_birth !== null && <p className={styles.place}>{person.place_of_birth}</p>}
          {!isPhone && biography}
        </div>
      </header>

      {isPhone && biography}

      <section className={styles.section} aria-labelledby={catalogId}>
        <h2 id={catalogId} className={styles.heading}>
          {S.person.inCatalog}
        </h2>
        {person.appearances.length > 0 ? (
          <PosterGrid items={person.appearances.map((a) => a.item)} subtitleFor={subtitleFor} />
        ) : (
          <p className={styles.empty}>{S.person.empty}</p>
        )}
        {!person.credits_complete && (
          <p className={styles.note} role="note">
            {S.person.incomplete}
          </p>
        )}
      </section>
    </div>
  );
}
