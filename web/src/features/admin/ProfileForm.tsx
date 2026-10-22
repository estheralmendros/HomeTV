// Create/edit profile modal (prd2 §11 Profile management): name, avatar, Administrador and the
// "PIN de administrador" section (required on create-as-admin and promote, keep/change for an
// admin, removed on demote). Edit sends only the changed fields; no `pin` for non-admins.
import { useId, useState, type FormEvent } from 'react';
import { errorMessage, isApiError, validationFields } from '@/api/errors';
import type { Avatar, Profile, ProfileCreateRequest, ProfileUpdateRequest } from '@/api/types';
import { isAdminActionCancelled } from '@/auth/useAdminAction';
import { Modal } from '@/components/Modal';
import { AvatarPicker } from '@/features/setup/AvatarPicker';
import { S } from '@/strings.es';
import styles from './Admin.module.css';

const T = S.admin.profileForm;
const PIN_PATTERN = /^\d{4}$/;
const NAME_MAX_LENGTH = 30;

export interface ProfileFormValues {
  name: string;
  avatarId: string | null;
  isAdmin: boolean;
  /** For an existing admin that stays admin. */
  pinMode: 'keep' | 'change';
  pin: string;
  pinConfirm: string;
}

type Field = 'name' | 'avatar' | 'pin' | 'pinConfirm';
type FieldErrors = Partial<Record<Field, string>>;

/** A PIN is required on create-as-admin, on promote, and when an admin chooses "Cambiar el PIN". */
export function pinRequired(original: Profile | null, v: ProfileFormValues): boolean {
  if (!v.isAdmin) return false;
  if (original === null || !original.is_admin) return true;
  return v.pinMode === 'change';
}

export function validateProfile(original: Profile | null, v: ProfileFormValues): FieldErrors {
  const errors: FieldErrors = {};
  const name = v.name.trim();
  if (name.length < 1 || name.length > NAME_MAX_LENGTH) errors.name = T.errors.name;
  if (!v.avatarId) errors.avatar = T.errors.avatar;
  if (pinRequired(original, v)) {
    if (!PIN_PATTERN.test(v.pin)) errors.pin = T.errors.pin;
    else if (v.pinConfirm !== v.pin) errors.pinConfirm = T.errors.pinMismatch;
  }
  return errors;
}

/** POST body (create) or the PATCH body with only the changed fields (edit). */
export function profileRequestBody(original: null, v: ProfileFormValues): ProfileCreateRequest;
export function profileRequestBody(original: Profile, v: ProfileFormValues): ProfileUpdateRequest;
export function profileRequestBody(original: Profile | null, v: ProfileFormValues): ProfileCreateRequest | ProfileUpdateRequest {
  const name = v.name.trim();
  const avatarId = v.avatarId ?? '';
  const withPin = pinRequired(original, v);
  if (original === null) {
    const body: ProfileCreateRequest = { name, avatar_id: avatarId, is_admin: v.isAdmin };
    if (withPin) body.pin = v.pin;
    return body;
  }
  const body: ProfileUpdateRequest = {};
  if (name !== original.name) body.name = name;
  if (avatarId !== original.avatar_id) body.avatar_id = avatarId;
  if (v.isAdmin !== original.is_admin) body.is_admin = v.isAdmin;
  if (withPin) body.pin = v.pin;
  return body;
}

export interface ProfileFormProps {
  /** null: create. */
  profile: Profile | null;
  avatars: Avatar[];
  onCancel: () => void;
  /** Runs the request (through useAdminAction); rejects with the ApiError to show. */
  onSubmit: (body: ProfileCreateRequest | ProfileUpdateRequest) => Promise<void>;
}

export function ProfileForm({ profile, avatars, onCancel, onSubmit }: ProfileFormProps) {
  const id = useId();
  const [values, setValues] = useState<ProfileFormValues>({
    name: profile?.name ?? '',
    avatarId: profile?.avatar_id ?? null,
    isAdmin: profile?.is_admin ?? false,
    pinMode: 'keep',
    pin: '',
    pinConfirm: '',
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const set = (patch: Partial<ProfileFormValues>) => setValues((v) => ({ ...v, ...patch }));

  const wasAdmin = profile?.is_admin === true;
  const demoting = wasAdmin && !values.isAdmin;
  const showPinInputs = pinRequired(profile, values);

  const onFormSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const found = validateProfile(profile, values);
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) return;
    const body = profile ? profileRequestBody(profile, values) : profileRequestBody(null, values);
    if (Object.keys(body).length === 0) {
      onCancel();
      return;
    }
    setSubmitting(true);
    try {
      await onSubmit(body);
    } catch (err) {
      setSubmitting(false);
      if (isAdminActionCancelled(err)) return;
      if (isApiError(err, 'validation_error')) {
        const fields = validationFields(err);
        const next: FieldErrors = {};
        if (fields.includes('name')) next.name = T.errors.name;
        if (fields.includes('avatar_id')) next.avatar = T.errors.avatar;
        if (fields.includes('pin')) next.pin = T.errors.pin;
        setErrors(next);
        if (Object.keys(next).length === 0) setFormError(errorMessage(err));
      } else {
        setFormError(errorMessage(err));
      }
    }
  };

  const describe = (field: Field) => (errors[field] ? `${id}-${field}-error` : undefined);
  const fieldError = (field: Field) =>
    errors[field] && (
      <p id={`${id}-${field}-error`} className={styles.error}>
        {errors[field]}
      </p>
    );

  return (
    <Modal title={profile ? T.editTitle : T.createTitle} onClose={onCancel}>
      <form className={styles.form} onSubmit={onFormSubmit} noValidate>
        <div className={styles.formField}>
          <label htmlFor={`${id}-name`}>{T.name}</label>
          <input
            id={`${id}-name`}
            className={styles.input}
            value={values.name}
            maxLength={60}
            autoComplete="off"
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={describe('name')}
            onChange={(e) => set({ name: e.target.value })}
          />
          {fieldError('name')}
        </div>

        <div className={styles.formField}>
          <span>{T.avatar}</span>
          <AvatarPicker
            avatars={avatars}
            name={values.name}
            value={values.avatarId}
            onChange={(avatarId) => set({ avatarId })}
            legend={T.avatar}
            errorId={describe('avatar')}
          />
          {fieldError('avatar')}
        </div>

        <label className={styles.checkbox}>
          <input type="checkbox" checked={values.isAdmin} onChange={(e) => set({ isAdmin: e.target.checked })} />
          {T.admin}
        </label>

        {values.isAdmin && (
          <fieldset className={styles.pinSection}>
            <legend>{T.pinSection}</legend>
            {wasAdmin && (
              <div className={styles.pinModes}>
                <label>
                  <input type="radio" name={`${id}-pin-mode`} checked={values.pinMode === 'keep'} onChange={() => set({ pinMode: 'keep' })} />
                  {T.keepPin}
                </label>
                <label>
                  <input
                    type="radio"
                    name={`${id}-pin-mode`}
                    checked={values.pinMode === 'change'}
                    onChange={() => set({ pinMode: 'change' })}
                  />
                  {T.changePin}
                </label>
              </div>
            )}
            {showPinInputs && (
              <div className={styles.pins}>
                <div className={styles.formField}>
                  <label htmlFor={`${id}-pin`}>{T.pin}</label>
                  <PinInput id={`${id}-pin`} value={values.pin} invalid={!!errors.pin} describedBy={describe('pin')} onChange={(pin) => set({ pin })} />
                </div>
                <div className={styles.formField}>
                  <label htmlFor={`${id}-pin-confirm`}>{T.pinConfirm}</label>
                  <PinInput
                    id={`${id}-pin-confirm`}
                    value={values.pinConfirm}
                    invalid={!!errors.pinConfirm}
                    describedBy={describe('pinConfirm')}
                    onChange={(pinConfirm) => set({ pinConfirm })}
                  />
                </div>
              </div>
            )}
            {fieldError('pin')}
            {fieldError('pinConfirm')}
          </fieldset>
        )}
        {demoting && <p className={styles.note}>{T.demoteNote}</p>}

        {formError && (
          <p className={styles.error} role="alert">
            {formError}
          </p>
        )}
        <div className={styles.actions}>
          <button type="button" className="button" onClick={onCancel}>
            {S.common.cancel}
          </button>
          <button type="submit" className="button button-primary" disabled={submitting}>
            {S.common.save}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function PinInput({
  id,
  value,
  invalid,
  describedBy,
  onChange,
}: {
  id: string;
  value: string;
  invalid: boolean;
  describedBy: string | undefined;
  onChange: (value: string) => void;
}) {
  return (
    <input
      id={id}
      className={`${styles.input} ${styles.pinInput}`}
      type="password"
      inputMode="numeric"
      pattern="\d{4}"
      maxLength={4}
      autoComplete="new-password"
      value={value}
      aria-invalid={invalid ? true : undefined}
      aria-describedby={describedBy}
      onChange={(e) => onChange(e.target.value.replace(/\D/g, '').slice(0, 4))}
    />
  );
}
