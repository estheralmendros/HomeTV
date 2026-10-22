import { Link } from 'react-router-dom';
import { useIsAdmin } from '@/auth/useCurrentProfile';
import { S } from '@/strings.es';
import { EmptyState } from './EmptyState';

/**
 * Empty library (Home, unfiltered grids): admins get the scan hint and a link through the admin
 * PIN gate (/admin/scan); viewers are asked to contact an admin.
 */
export function EmptyLibrary() {
  const isAdmin = useIsAdmin();
  if (!isAdmin) return <EmptyState message={S.home.emptyViewer} />;
  return (
    <EmptyState message={S.home.emptyAdmin}>
      <Link to="/admin/scan" className="button button-primary">
        {S.home.goToScan}
      </Link>
    </EmptyState>
  );
}
