import { Outlet } from 'react-router-dom';
import styles from './AppShell.module.css';
import { CrossTabBanner } from './CrossTabBanner';
import { Footer } from './Footer';
import { MobileTabBar, NavBar } from './NavBar';

/** Layout of every non-player page: NavBar, cross-tab banner, page, TMDB footer, tab bar. */
export function AppShell() {
  return (
    <div className={styles.shell}>
      <NavBar />
      <CrossTabBanner />
      <main className={styles.main}>
        <Outlet />
      </main>
      <Footer />
      <MobileTabBar />
    </div>
  );
}
