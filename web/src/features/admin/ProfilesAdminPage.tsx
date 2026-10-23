// /admin/profiles (prd2 §11 Profile management): list, create/edit (ProfileForm) and delete with
// confirmation. Every mutation runs through useAdminAction. Editing your own PIN or admin flag
// revokes only your admin token (prd.md §8): the profile session stays.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createProfile, deleteProfile, updateProfile } from '@/api/admin';
import { errorMessage } from '@/api/errors';
import { infoQuery, profilesQuery } from '@/api/queries';
import { runInvalidation } from '@/api/queryClient';
import { queryKeys } from '@/api/queryKeys';
import type { Profile, ProfileCreateRequest, ProfileUpdateRequest } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { adminUnlock } from '@/auth/adminUnlock';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { useCurrentProfile } from '@/auth/useCurrentProfile';
import { ProfileAvatar } from '@/components/Avatar';
import { ErrorState } from '@/components/ErrorState';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { S } from '@/strings.es';
import styles from './Admin.module.css';
import { ProfileForm } from './ProfileForm';

const T = S.admin.profiles;
export const MAX_PROFILES = 12;

type Editing = { kind: 'create' } | { kind: 'edit'; profile: Profile } | null;

export function ProfilesAdminPage() {
  const profiles = useQuery(profilesQuery());
  const { data: info } = useQuery(infoQuery());
  const me = useCurrentProfile();
  const queryClient = useQueryClient();
  const runAdmin = useAdminAction();
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<Profile | null>(null);
  const limitId = useId();

  const items = profiles.data?.items;
  const atLimit = (items?.length ?? 0) >= MAX_PROFILES;

  const onCreate = async (body: ProfileCreateRequest | ProfileUpdateRequest) => {
    await runAdmin(() => createProfile(body as ProfileCreateRequest), T.newProfile);
    setEditing(null);
    void runInvalidation(queryClient, { type: 'profilesChanged' });
  };

  const onEdit = (profile: Profile) => async (body: ProfileCreateRequest | ProfileUpdateRequest) => {
    const updated = await runAdmin(() => updateProfile(profile.id, body), S.admin.profileForm.editTitle);
    setEditing(null);
    const self = profile.id === me?.id;
    if (self && ('pin' in body || 'is_admin' in body)) {
      // The server revoked this profile's admin tokens; the profile token stays valid.
      if (!updated.is_admin) {
        navigate('/', { replace: true });
        queryClient.setQueryData(queryKeys.me(), updated);
      }
      adminUnlock.clear();
    }
    void runInvalidation(queryClient, { type: 'profilesChanged' });
  };

  const onDelete = async (profile: Profile) => {
    await runAdmin(() => deleteProfile(profile.id), T.deleteLabel(profile.name));
    setDeleting(null);
    if (profile.id === me?.id) {
      signOut();
      return;
    }
    void runInvalidation(queryClient, { type: 'profilesChanged' });
  };

  return (
    <div className={styles.panel}>
      <h2 className="visually-hidden">{T.title}</h2>
      <div className={styles.toolbar}>
        <span title={atLimit ? T.limitReached : undefined}>
          <button
            type="button"
            className="button button-primary"
            disabled={!items || atLimit}
            aria-describedby={atLimit ? limitId : undefined}
            onClick={() => setEditing({ kind: 'create' })}
          >
            {T.newProfile}
          </button>
          {atLimit && (
            <span id={limitId} className="visually-hidden">
              {T.limitReached}
            </span>
          )}
        </span>
      </div>

      {items ? (
        <ul className={styles.rows} aria-label={T.title}>
          {items.map((profile) => (
            <ProfileRow
              key={profile.id}
              profile={profile}
              onEdit={() => setEditing({ kind: 'edit', profile })}
              onDelete={() => setDeleting(profile)}
            />
          ))}
        </ul>
      ) : profiles.isError ? (
        <ErrorState message={errorMessage(profiles.error)} onRetry={() => void profiles.refetch()} />
      ) : (
        <Spinner />
      )}

      {editing && (
        <ProfileForm
          profile={editing.kind === 'edit' ? editing.profile : null}
          avatars={info?.avatars ?? []}
          onCancel={() => setEditing(null)}
          onSubmit={editing.kind === 'edit' ? onEdit(editing.profile) : onCreate}
        />
      )}
      {deleting && <DeleteDialog profile={deleting} onCancel={() => setDeleting(null)} onConfirm={() => onDelete(deleting)} />}
    </div>
  );
}

function ProfileRow({ profile, onEdit, onDelete }: { profile: Profile; onEdit: () => void; onDelete: () => void }) {
  const nameId = useId();
  return (
    <li className={styles.profileRow} aria-labelledby={nameId}>
      <ProfileAvatar name={profile.name} avatarId={profile.avatar_id} size="sm" />
      <span id={nameId} className={styles.profileName}>
        {profile.name}
      </span>
      {profile.is_admin && <span className={styles.tag}>{S.common.adminTag}</span>}
      <span className={styles.rowActions}>
        <button type="button" className="button" aria-label={T.editLabel(profile.name)} onClick={onEdit}>
          {S.common.edit}
        </button>
        <button type="button" className="button" aria-label={T.deleteLabel(profile.name)} onClick={onDelete}>
          {S.common.delete}
        </button>
      </span>
    </li>
  );
}

function DeleteDialog({ profile, onCancel, onConfirm }: { profile: Profile; onCancel: () => void; onConfirm: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
    } catch (err) {
      setBusy(false);
      if (!isAdminActionCancelled(err)) setError(errorMessage(err));
    }
  };
  return (
    <Modal title={T.deleteTitle(profile.name)} onClose={onCancel}>
      <p>{T.deleteBody}</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.actions}>
        <button type="button" className="button" onClick={onCancel}>
          {S.common.cancel}
        </button>
        <button type="button" className="button button-primary" disabled={busy} onClick={() => void confirm()}>
          {S.common.delete}
        </button>
      </div>
    </Modal>
  );
}
