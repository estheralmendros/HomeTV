import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { useCurrentProfile } from '@/auth/useCurrentProfile';
import { NavSearch } from '@/features/search/NavSearch';
import { S } from '@/strings.es';
import { ProfileAvatar } from './Avatar';
import styles from './NavBar.module.css';

const SECTIONS = [
  { to: '/', label: S.nav.home, end: true },
  { to: '/films', label: S.nav.films, end: false },
  { to: '/shows', label: S.nav.shows, end: false },
] as const;

/** Top bar: logo, section links, search and the avatar menu. Below 720 px see MobileTabBar. */
export function NavBar() {
  return (
    <header className={styles.bar}>
      <Link to="/" className={styles.logo} aria-label={S.nav.logo}>
        <span className={styles.logoMark} aria-hidden="true">
          ▶
        </span>
        <span aria-hidden="true">{S.app.name}</span>
      </Link>
      <nav aria-label={S.nav.mainLabel} className={styles.desktopOnly}>
        <ul className={styles.links}>
          {SECTIONS.map((section) => (
            <li key={section.to}>
              <NavLink to={section.to} end={section.end} className={styles.link}>
                {section.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <span className={styles.spacer} />
      <div className={styles.desktopOnly}>
        <NavSearch />
      </div>
      <AvatarMenu />
    </header>
  );
}

function AvatarMenu() {
  const profile = useCurrentProfile();
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  if (!profile) return null;

  const go = (to: string) => {
    setOpen(false);
    navigate(to);
  };

  return (
    <div className={styles.menuWrap} ref={wrapRef}>
      <button
        type="button"
        className={styles.menuButton}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={S.nav.profileMenu(profile.name)}
        onClick={() => setOpen((v) => !v)}
      >
        <ProfileAvatar name={profile.name} avatarId={profile.avatar_id} size="sm" />
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <ul className={styles.menu} role="menu">
          <li role="none">
            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => go('/profiles')}>
              {S.nav.switchProfile}
            </button>
          </li>
          {profile.is_admin && (
            <li role="none">
              <button type="button" role="menuitem" className={styles.menuItem} onClick={() => go('/admin')}>
                {S.nav.admin}
              </button>
            </li>
          )}
          <li role="none">
            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => go('/about')}>
              {S.nav.about}
            </button>
          </li>
          <li role="none">
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              onClick={() => {
                setOpen(false);
                signOut();
              }}
            >
              {S.nav.signOut}
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}

/** Below 720 px: bottom tab bar "Inicio", "Películas", "Series", "Buscar" (CSS shows it). */
export function MobileTabBar() {
  return (
    <nav aria-label={S.nav.tabsLabel} className={styles.tabBar}>
      {[...SECTIONS, { to: '/search', label: S.nav.search, end: false }].map((tab) => (
        <NavLink key={tab.to} to={tab.to} end={tab.end} className={styles.tab}>
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
