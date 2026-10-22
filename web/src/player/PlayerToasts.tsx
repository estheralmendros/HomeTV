// Player-local toasts (D11 notice, audio/subtitle failures, the cross-tab notice). They render
// inside the player root, so they stay visible in element fullscreen (the global toast region
// sits outside the fullscreen container).
import { useCallback, useEffect, useRef, useState } from 'react';
import { S } from '@/strings.es';
import styles from './Player.module.css';

export interface PlayerToast {
  id: number;
  message: string;
  action?: { label: string; onClick: () => void };
  onDismiss?: () => void;
}

export const PLAYER_TOAST_MS = 4000;

export function usePlayerToasts() {
  const [toasts, setToasts] = useState<PlayerToast[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: number) => setToasts((list) => list.filter((t) => t.id !== id)), []);

  const show = useCallback(
    (message: string, durationMs = PLAYER_TOAST_MS) => {
      const id = nextId.current++;
      setToasts((list) => [...list, { id, message }]);
      const timer = setTimeout(() => {
        timers.current.delete(timer);
        dismiss(id);
      }, durationMs);
      timers.current.add(timer);
    },
    [dismiss],
  );

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach(clearTimeout);
      pending.clear();
    };
  }, []);

  return { toasts, show, dismiss };
}

export function PlayerToasts({ toasts, onDismiss }: { toasts: readonly PlayerToast[]; onDismiss: (toast: PlayerToast) => void }) {
  return (
    <div className={styles.toasts} role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={styles.toast}>
          <p className={styles.toastText}>{toast.message}</p>
          {toast.action && (
            <button type="button" className={styles.toastButton} onClick={toast.action.onClick}>
              {toast.action.label}
            </button>
          )}
          {toast.onDismiss && (
            <button type="button" className={styles.toastButton} aria-label={S.toast.dismiss} onClick={() => onDismiss(toast)}>
              ✕
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
