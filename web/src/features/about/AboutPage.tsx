import { useQuery } from '@tanstack/react-query';
import { errorMessage } from '@/api/errors';
import { infoQuery } from '@/api/queries';
import tmdbLogo from '@/assets/tmdb-logo.svg';
import { ErrorState } from '@/components/ErrorState';
import { Spinner } from '@/components/Spinner';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import styles from './AboutPage.module.css';

export const TMDB_URL = 'https://www.themoviedb.org';

export function AboutPage() {
  useDocumentTitle(S.titles.about);
  const info = useQuery(infoQuery());
  return (
    <section className={`page ${styles.about}`}>
      <h1>{S.about.title}</h1>
      {info.isPending && <Spinner />}
      {info.isError && <ErrorState message={errorMessage(info.error)} onRetry={() => void info.refetch()} />}
      {info.data && (
        <>
          <h2>{info.data.name}</h2>
          <p>{S.about.serverVersion(info.data.version)}</p>
          <img className={styles.logo} src={tmdbLogo} alt={S.about.tmdbLogoAlt} width={160} />
          <p>{info.data.tmdb_attribution}</p>
          <p>
            <a href={TMDB_URL} target="_blank" rel="noreferrer">
              {S.about.tmdbLink}
            </a>
          </p>
          <p className={styles.muted}>{S.about.privacy}</p>
        </>
      )}
    </section>
  );
}
