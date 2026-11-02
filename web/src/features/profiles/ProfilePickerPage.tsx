// "¿Quién está viendo?": one click signs in to any profile; no PIN for anyone.
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { errorMessage, isApiError } from '@/api/errors';
import { createSession } from '@/api/profiles';
import { profilesQuery } from '@/api/queries';
import type { Profile } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import type { FromState } from '@/auth/RequireProfile';
import { readLastProfileId } from '@/auth/session';
import { ProfileAvatar } from '@/components/Avatar';
import { ErrorState } from '@/components/ErrorState';
import { FullScreenSpinner } from '@/components/Spinner';
import { useToast } from '@/components/Toast';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import styles from './ProfilePickerPage.module.css';

/** Only same-app paths are followed after a pick. */
function safeFrom(state: unknown): string {
  const from = (state as FromState | null)?.from;
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') && !from.startsWith('/profiles')
    ? from
    : '/';
}

export function ProfilePickerPage() {
  useDocumentTitle(S.titles.profiles);
  const profiles = useQuery(profilesQuery());
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const toast = useToast();
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [lastProfileId] = useState(readLastProfileId);

  const pick = async (profile: Profile) => {
    setPendingId(profile.id);
    try {
      const response = await createSession(profile.id);
      signIn(response);
      navigate(safeFrom(location.state), { replace: true });
    } catch (err) {
      setPendingId(null);
      if (isApiError(err, 'profile_not_found')) {
        void profiles.refetch();
        toast.show(S.profiles.notFound);
      } else {
        toast.show(errorMessage(err));
      }
    }
  };

  if (profiles.isPending) return <FullScreenSpinner />;
  if (profiles.isError) {
    return <ErrorState message={errorMessage(profiles.error)} onRetry={() => void profiles.refetch()} />;
  }

  const items = profiles.data.items;
  const focusId = items.some((p) => p.id === lastProfileId) ? lastProfileId : items[0]?.id;

  return (
    <main className={styles.page}>
      {searchParams.get('reason') === 'expired' && (
        <p className={styles.banner} role="status">
          {S.profiles.expired}
        </p>
      )}
      <h1>{S.profiles.title}</h1>
      {items.length === 0 ? (
        <ErrorState message={S.profiles.empty}>
          <Link to="/setup" className="button">
            {S.profiles.goToSetup}
          </Link>
        </ErrorState>
      ) : (
        <ul className={styles.grid}>
          {items.map((profile) => (
            <li key={profile.id}>
              <button
                type="button"
                className={styles.profile}
                disabled={pendingId !== null}
                // The most recently chosen profile gets the initial focus.
                autoFocus={profile.id === focusId}
                onClick={() => void pick(profile)}
              >
                <ProfileAvatar name={profile.name} avatarId={profile.avatar_id} size="lg" />
                <span className={styles.name}>{profile.name}</span>
                {profile.is_admin && <span className={styles.tag}>{S.profiles.adminTag}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
