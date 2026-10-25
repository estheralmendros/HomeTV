import { Outlet } from 'react-router-dom';
import { COMPACT_NAV_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import styles from './AppShell.module.css';
import { CrossTabBanner } from './CrossTabBanner';
import { MobileTabBar, NavBar } from './NavBar';

/**
 * Layout of every non-player page: NavBar, cross-tab banner, page, tab bar (TMDB attribution:
 * /about only). Below 720 px the fixed MobileTabBar would cover the end of the page, so the
 * shell reserves its height (plus the safe-area inset) at the bottom.
 */
export function AppShell() {
  const compact = useMediaQuery(COMPACT_NAV_QUERY);
  return (
    <div className={compact ? `${styles.shell} ${styles.withTabBar}` : styles.shell}>
      <NavBar />
      <CrossTabBanner />
      <main className={styles.main}>
        <Outlet />
      </main>
      <MobileTabBar />
    </div>
  );
}
