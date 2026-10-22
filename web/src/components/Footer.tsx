import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { infoQuery } from '@/api/queries';
import tmdbLogo from '@/assets/tmdb-logo.svg';
import { S } from '@/strings.es';
import styles from './Footer.module.css';

/** TMDB logo (≤ 24 px) + `info.tmdb_attribution` (Spanish, as received), linking to /about. */
export function Footer() {
  const { data: info } = useQuery(infoQuery());
  return (
    <footer className={styles.footer}>
      <Link to="/about" className={styles.link} title={S.footer.aboutLink}>
        <img className={styles.logo} src={tmdbLogo} alt={S.footer.tmdbLogoAlt} height={20} />
        {info && <span>{info.tmdb_attribution}</span>}
      </Link>
    </footer>
  );
}
