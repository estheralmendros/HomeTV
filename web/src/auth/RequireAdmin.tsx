// /admin/*: is_admin check (no redirect loop for viewers), then the admin unlock gate. A token
// lost mid-session (admin_locked from a query, expiry) re-opens the keypad over the page.
import { useEffect, useState } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { FullScreenSpinner } from '@/components/Spinner';
import { useGoBack } from '@/lib/useGoBack';
import { S } from '@/strings.es';
import { useRequestAdminUnlock } from './AdminPinProvider';
import { useAdminToken } from './useAdminAction';
import { useCurrentProfile } from './useCurrentProfile';

export function RequireAdmin() {
  const profile = useCurrentProfile();
  const isAdmin = profile?.is_admin === true;
  const token = useAdminToken();
  const requestUnlock = useRequestAdminUnlock();
  const navigate = useNavigate();
  const goBack = useGoBack('/');
  const [entered, setEntered] = useState(token !== null);

  useEffect(() => {
    if (token !== null) setEntered(true);
  }, [token]);

  useEffect(() => {
    if (!isAdmin || token !== null) return undefined;
    let active = true;
    void requestUnlock(S.admin.title).then((unlocked) => {
      if (!active || unlocked) return;
      // Cancel: leave the admin section (back to where the link was, or Home after a re-lock).
      if (entered) navigate('/', { replace: true });
      else goBack();
    });
    return () => {
      active = false;
    };
  }, [entered, goBack, isAdmin, navigate, requestUnlock, token]);

  if (!isAdmin) {
    return (
      <div className="page" role="alert">
        <p>{S.admin.onlyAdmins}</p>
        <Link to="/" className="button">
          {S.common.goHome}
        </Link>
      </div>
    );
  }
  return entered ? <Outlet /> : <FullScreenSpinner />;
}
