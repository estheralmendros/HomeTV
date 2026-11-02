// On-screen numeric keypad for the admin PIN: 0-9, ⌫, OK, physical
// digits/Backspace/Enter, 4 masked dots, auto-submit at 4 digits.
import { useCallback, useEffect, useRef, useState } from 'react';
import { unlockAdmin } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { ProfileAvatar } from '@/components/Avatar';
import { Modal } from '@/components/Modal';
import { cx } from '@/lib/cx';
import { formatDuration } from '@/lib/format';
import { S } from '@/strings.es';
import { adminUnlock } from './adminUnlock';
import styles from './AdminPinModal.module.css';
import { useCurrentProfile } from './useCurrentProfile';

const PIN_LENGTH = 4;
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as const;

export interface AdminPinModalProps {
  /** What the PIN is for, e.g. "Escanear ahora". */
  purpose?: string;
  onUnlocked: () => void;
  onCancel: () => void;
  /** 403 forbidden: the profile was demoted meanwhile. */
  onForbidden: () => void;
}

export function AdminPinModal({ purpose, onUnlocked, onCancel, onForbidden }: AdminPinModalProps) {
  const profile = useCurrentProfile();
  const [digits, setDigits] = useState('');
  const digitsRef = useRef(digits);
  digitsRef.current = digits;
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [shake, setShake] = useState(false);
  const [lockedSeconds, setLockedSeconds] = useState(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const locked = lockedSeconds > 0;
  const disabled = busy || locked;

  useEffect(() => {
    if (!locked) return undefined;
    const timer = setInterval(() => setLockedSeconds((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(timer);
  }, [locked]);

  const submit = useCallback(
    async (pin: string) => {
      setBusy(true);
      setMessage(null);
      try {
        const response = await unlockAdmin(pin);
        adminUnlock.set(response);
        if (mounted.current) onUnlocked();
      } catch (err) {
        if (!mounted.current) return;
        digitsRef.current = '';
        setDigits('');
        if (isApiError(err, 'invalid_pin')) {
          setShake(true);
          setMessage(S.adminPin.invalid);
        } else if (isApiError(err, 'too_many_attempts')) {
          setLockedSeconds(Math.max(1, Math.ceil(err.retryAfter ?? 60)));
        } else if (isApiError(err, 'forbidden')) {
          onForbidden();
        } else {
          setMessage(errorMessage(err));
        }
      } finally {
        if (mounted.current) setBusy(false);
      }
    },
    [onForbidden, onUnlocked],
  );

  const press = useCallback(
    (digit: string) => {
      const current = digitsRef.current;
      if (disabled || current.length >= PIN_LENGTH) return;
      const next = current + digit;
      digitsRef.current = next;
      setShake(false);
      setDigits(next);
      if (next.length === PIN_LENGTH) void submit(next);
    },
    [disabled, submit],
  );

  const backspace = useCallback(() => {
    if (disabled) return;
    digitsRef.current = digitsRef.current.slice(0, -1);
    setDigits(digitsRef.current);
  }, [disabled]);

  const confirm = useCallback(() => {
    if (!disabled && digits.length === PIN_LENGTH) void submit(digits);
  }, [digits, disabled, submit]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (/^[0-9]$/.test(event.key)) {
        event.preventDefault();
        press(event.key);
      } else if (event.key === 'Backspace') {
        event.preventDefault();
        backspace();
      } else if (event.key === 'Enter') {
        event.preventDefault();
        confirm();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [backspace, confirm, press]);

  const shownMessage = locked ? S.adminPin.tooManyAttempts(formatDuration(lockedSeconds)) : message;

  return (
    <Modal title={S.adminPin.title} onClose={onCancel} className={styles.modal}>
      {profile && (
        <div className={styles.who}>
          <ProfileAvatar name={profile.name} avatarId={profile.avatar_id} size="sm" />
          <span>{profile.name}</span>
        </div>
      )}
      {purpose && <p className={styles.purpose}>{S.adminPin.purpose(purpose)}</p>}
      <div
        className={cx(styles.dots, shake && styles.shake)}
        role="img"
        aria-label={S.adminPin.progress(digits.length)}
        data-testid="pin-dots"
        data-shake={shake || undefined}
        onAnimationEnd={() => setShake(false)}
      >
        {Array.from({ length: PIN_LENGTH }, (_, i) => (
          <span key={i} aria-hidden="true">
            {i < digits.length ? '●' : '○'}
          </span>
        ))}
      </div>
      <div className={styles.keypad} role="group" aria-label={S.adminPin.keypad}>
        {KEYS.map((key) => (
          <button key={key} type="button" className={styles.key} disabled={disabled} onClick={() => press(key)}>
            {key}
          </button>
        ))}
        <button type="button" className={styles.key} disabled={disabled} aria-label={S.adminPin.backspace} onClick={backspace}>
          ⌫
        </button>
        <button type="button" className={styles.key} disabled={disabled} onClick={() => press('0')}>
          0
        </button>
        <button
          type="button"
          className={styles.key}
          disabled={disabled || digits.length < PIN_LENGTH}
          aria-label={S.adminPin.okLabel}
          onClick={confirm}
        >
          {S.adminPin.ok}
        </button>
      </div>
      <p className={styles.message} role="alert">
        {shownMessage}
      </p>
      <div className={styles.footer}>
        <button type="button" className="button" onClick={onCancel}>
          {S.common.cancel}
        </button>
      </div>
    </Modal>
  );
}
