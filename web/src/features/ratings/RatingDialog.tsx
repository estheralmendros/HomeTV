// "¿Qué te ha parecido?" / manual rating (ratings prd2 FR-W7). RatingForm is the content (the
// player renders it inline: `Modal` portals to <body>, invisible in element fullscreen);
// RatingDialog wraps it in a Modal. One secondary answer for Escape, the Modal's close and
// [Ahora no] / [Cancelar]; nothing reacts while a save is pending (the buttons are `aria-disabled`,
// not `disabled`, so the focused one keeps the focus). A failed save moves the focus to
// [Reintentar]. In prompt mode the form marks the title handled on the host at the first save
// attempt and at Ahora no.
import { useEffect, useRef, useState, type FormEvent, type MutableRefObject } from 'react';
import { isApiError } from '@/api/errors';
import type { RatingPrompt } from '@/api/types';
import { Artwork } from '@/components/Artwork';
import { Modal } from '@/components/Modal';
import { StarInput } from '@/components/StarRating';
import { S } from '@/strings.es';
import styles from './Ratings.module.css';
import { useRatingPrompt } from './ratingPrompt';
import { dismissInBackground, useRatingWrite } from './useRatingWrite';

export interface RatingFormProps {
  mode: 'prompt' | 'manual';
  target: RatingPrompt;
  initial: number | null;
  /**
   * The form is done (Guardar success, Ahora no, Cancelar, Quitar puntuación, 404…). Optional for
   * the inline player form, whose end state follows `onAnswered`; RatingDialog requires it.
   */
  onClose?: () => void;
  /** Prompt mode: successful save, Ahora no (button or Escape), 404. */
  onAnswered?: () => void;
  /** Toasts while mounted (default: the app toast; the player passes its own). */
  notify?: (text: string) => void;
  /** Not inside a Modal (the player end state): the form handles Escape itself. */
  inline?: boolean;
}

interface InternalProps extends RatingFormProps {
  /** RatingDialog's Modal close/Escape call the form's secondary answer through this (the form keeps it current). */
  secondaryRef?: MutableRefObject<() => void>;
}

export function RatingForm({ mode, target, initial, onClose, onAnswered, notify, inline = false, secondaryRef }: InternalProps) {
  const { kind, id } = target;
  const { markHandled } = useRatingPrompt();
  const write = useRatingWrite(kind, id, { notify });
  const [value, setValue] = useState<number | null>(initial);
  const [failed, setFailed] = useState(false);
  const saving = write.saving;

  const save = () => {
    if (value === null || saving) return;
    if (mode === 'prompt') markHandled(kind, id);
    setFailed(false);
    write.save(value, {
      onSuccess: () => {
        onAnswered?.();
        onClose?.();
      },
      onError: (err) => {
        if (isApiError(err) && err.status === 404) {
          onAnswered?.();
          onClose?.();
        } else setFailed(true);
      },
    });
  };

  const answerSecondary = () => {
    if (saving) return;
    if (mode === 'prompt') {
      markHandled(kind, id);
      dismissInBackground(kind, id);
      onAnswered?.();
    }
    onClose?.();
  };
  // The latest answer; inside RatingDialog the Modal's ref, so its close/Escape call it too.
  const ownSecondary = useRef(answerSecondary);
  const secondary = secondaryRef ?? ownSecondary;
  secondary.current = answerSecondary;

  const removeRating = () => {
    if (saving) return;
    write.remove();
    onClose?.();
  };

  // Inline: Escape = the secondary answer (Modal does this for the dialog).
  useEffect(() => {
    if (!inline) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      secondary.current();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [inline, secondary]);

  // Inline: focus the stars (inside a Modal, the Modal focuses its first focusable, the stars).
  const starsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (inline) starsRef.current?.querySelector<HTMLElement>('[role="slider"]')?.focus();
  }, [inline]);

  // A failed save: [Guardar] became [Reintentar]; focus it (the save may have come from the stars).
  const submitRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (failed) submitRef.current?.focus();
  }, [failed]);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    save();
  };

  return (
    <form className={styles.form} onSubmit={onSubmit}>
      <div className={styles.target}>
        <Artwork image={target.poster} title={target.title} kind="poster" className={styles.poster} largeAt2x={false} />
        <div className={styles.targetText}>
          <p className={styles.targetTitle}>{target.title}</p>
          {mode === 'prompt' && <p className={styles.question}>{S.ratings.question}</p>}
        </div>
      </div>
      <div ref={starsRef} className={styles.stars}>
        <StarInput value={value} onChange={setValue} onEnter={save} disabled={saving} />
      </div>
      {failed && (
        <p className={styles.error} role="alert">
          {S.ratings.saveError}
        </p>
      )}
      <div className={styles.buttons}>
        {mode === 'manual' && initial !== null && (
          <button type="button" className={styles.remove} aria-disabled={saving || undefined} onClick={removeRating}>
            {S.ratings.remove}
          </button>
        )}
        <span className={styles.spacer} />
        <button ref={submitRef} type="submit" className="button button-primary" disabled={value === null} aria-disabled={saving || undefined}>
          {failed ? S.common.retry : S.common.save}
        </button>
        <button type="button" className="button" aria-disabled={saving || undefined} onClick={answerSecondary}>
          {mode === 'prompt' ? S.ratings.notNow : S.common.cancel}
        </button>
      </div>
    </form>
  );
}

export type RatingDialogProps = RatingFormProps & { onClose: () => void };

/** RatingForm inside a Modal titled with the title; Escape / close = the secondary answer. */
export function RatingDialog(props: RatingDialogProps) {
  const secondaryRef = useRef<() => void>(() => undefined);
  return (
    <Modal title={props.target.title} onClose={() => secondaryRef.current()} className={styles.dialog}>
      <RatingForm {...props} inline={false} secondaryRef={secondaryRef} />
    </Modal>
  );
}
