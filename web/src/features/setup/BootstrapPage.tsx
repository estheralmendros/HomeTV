// First run (/setup): name, avatar and a required, confirmed 4-digit admin PIN.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { isApiError, errorMessage, validationFields } from '@/api/errors';
import { bootstrap } from '@/api/profiles';
import { infoQuery } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import type { Info } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import { AvatarPicker } from './AvatarPicker';
import styles from './BootstrapPage.module.css';

const PIN_PATTERN = /^\d{4}$/;
export const NAME_MAX_LENGTH = 30;

type Field = 'name' | 'avatar' | 'pin' | 'pinConfirm';
type FieldErrors = Partial<Record<Field, string>>;

export function validateBootstrap(values: { name: string; avatarId: string | null; pin: string; pinConfirm: string }): FieldErrors {
  const errors: FieldErrors = {};
  const name = values.name.trim();
  if (name.length < 1 || name.length > NAME_MAX_LENGTH) errors.name = S.setup.errors.name;
  if (!values.avatarId) errors.avatar = S.setup.errors.avatar;
  if (!PIN_PATTERN.test(values.pin)) errors.pin = S.setup.errors.pin;
  else if (values.pinConfirm !== values.pin) errors.pinConfirm = S.setup.errors.pinMismatch;
  return errors;
}

/** Marks the server as bootstrapped in the cache (so the boot gate stops redirecting) and refetches. */
function markBootstrapped(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.setQueryData<Info>(queryKeys.info(), (old) => (old ? { ...old, needs_bootstrap: false } : old));
  void queryClient.invalidateQueries({ queryKey: queryKeys.info() });
}

export function BootstrapPage() {
  useDocumentTitle(S.titles.setup);
  const { data: info } = useQuery(infoQuery());
  const queryClient = useQueryClient();
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [avatarId, setAvatarId] = useState<string | null>(null);
  const [pin, setPin] = useState('');
  const [pinConfirm, setPinConfirm] = useState('');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const found = validateBootstrap({ name, avatarId, pin, pinConfirm });
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0 || !avatarId) return;
    setSubmitting(true);
    try {
      const response = await bootstrap({ name: name.trim(), avatar_id: avatarId, pin });
      signIn(response, { clearCache: false });
      markBootstrapped(queryClient);
      navigate('/', { replace: true });
    } catch (err) {
      if (isApiError(err, 'already_bootstrapped')) {
        markBootstrapped(queryClient);
        navigate('/profiles', { replace: true });
      } else if (isApiError(err, 'validation_error')) {
        const fields = validationFields(err);
        const next: FieldErrors = {};
        if (fields.includes('name')) next.name = S.setup.errors.name;
        if (fields.includes('avatar_id')) next.avatar = S.setup.errors.avatar;
        if (fields.includes('pin')) next.pin = S.setup.errors.pin;
        setErrors(next);
        if (Object.keys(next).length === 0) setFormError(errorMessage(err));
      } else {
        setFormError(errorMessage(err));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const describe = (field: Field) => (errors[field] ? `setup-${field}-error` : undefined);

  return (
    <main className={styles.page}>
      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <h1>{S.setup.title}</h1>
        <p className={styles.help}>{S.setup.intro}</p>

        <div className={styles.field}>
          <label htmlFor="setup-name">{S.setup.name}</label>
          <input
            id="setup-name"
            className={styles.input}
            value={name}
            maxLength={60}
            autoComplete="off"
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={describe('name')}
            onChange={(e) => setName(e.target.value)}
          />
          {errors.name && (
            <p id="setup-name-error" className={styles.error}>
              {errors.name}
            </p>
          )}
        </div>

        <div className={styles.field}>
          <span>{S.setup.avatar}</span>
          <AvatarPicker
            avatars={info?.avatars ?? []}
            name={name}
            value={avatarId}
            onChange={setAvatarId}
            legend={S.setup.avatar}
            errorId={describe('avatar')}
          />
          {errors.avatar && (
            <p id="setup-avatar-error" className={styles.error}>
              {errors.avatar}
            </p>
          )}
        </div>

        <div className={styles.pins}>
          <div className={styles.field}>
            <label htmlFor="setup-pin">{S.setup.pin}</label>
            <input
              id="setup-pin"
              className={styles.input}
              type="password"
              inputMode="numeric"
              pattern="\d{4}"
              maxLength={4}
              autoComplete="new-password"
              value={pin}
              aria-invalid={errors.pin ? true : undefined}
              aria-describedby={describe('pin') ?? 'setup-pin-help'}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
            />
          </div>
          <div className={styles.field}>
            <label htmlFor="setup-pin-confirm">{S.setup.pinConfirm}</label>
            <input
              id="setup-pin-confirm"
              className={styles.input}
              type="password"
              inputMode="numeric"
              pattern="\d{4}"
              maxLength={4}
              autoComplete="new-password"
              value={pinConfirm}
              aria-invalid={errors.pinConfirm ? true : undefined}
              aria-describedby={describe('pinConfirm')}
              onChange={(e) => setPinConfirm(e.target.value.replace(/\D/g, '').slice(0, 4))}
            />
          </div>
        </div>
        <p id="setup-pin-help" className={styles.help}>
          {S.setup.pinHelp}
        </p>
        {errors.pin && (
          <p id="setup-pin-error" className={styles.error}>
            {errors.pin}
          </p>
        )}
        {errors.pinConfirm && (
          <p id="setup-pinConfirm-error" className={styles.error}>
            {errors.pinConfirm}
          </p>
        )}
        {formError && (
          <p className={styles.error} role="alert">
            {formError}
          </p>
        )}
        <button type="submit" className="button button-primary" disabled={submitting}>
          {S.setup.submit}
        </button>
      </form>
    </main>
  );
}
