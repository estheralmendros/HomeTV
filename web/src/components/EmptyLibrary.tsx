import { Link } from 'react-router-dom';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { S } from '@/strings.es';
import { EmptyState } from './EmptyState';

/**
 * Empty library (Home, unfiltered grids): admins get the folders/scan hint and links through the
 * admin PIN gate (/admin/libraries first, then /admin/scan); viewers are asked to contact an admin.
 */
export function EmptyLibrary() {
  const isAdmin = useIsAdmin();
  if (!isAdmin) return <EmptyState message={S.home.emptyViewer} />;
  return (
    <EmptyState message={S.home.emptyAdmin}>
      <Link to="/admin/libraries" className="button button-primary">
        {S.home.goToLibraries}
      </Link>
      <Link to="/admin/scan" className="button">
        {S.home.goToScan}
      </Link>
    </EmptyState>
  );
}
