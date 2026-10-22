// "Corregir identificación" on detail pages (admins only; the caller decides). Opening the
// dialog is an admin op: useAdminAction asks for the PIN when locked. The dialog module is lazy.
import { lazy, Suspense, useState } from 'react';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { S } from '@/strings.es';
import type { FixMatchMode, MatchedTarget } from './fixMatchTypes';

const FixMatchDialog = lazy(() => import('./FixMatchDialog').then((m) => ({ default: m.FixMatchDialog })));

export function FixMatchButton({ mode, onMatched }: { mode: FixMatchMode; onMatched?: (target: MatchedTarget) => void }) {
  const runAdmin = useAdminAction();
  const [open, setOpen] = useState(false);
  const onClick = () => {
    runAdmin(async () => setOpen(true), S.film.fixMatch).catch((err: unknown) => {
      if (!isAdminActionCancelled(err)) console.warn('fix match gate failed', err);
    });
  };
  return (
    <>
      <button type="button" className="button" onClick={onClick}>
        {S.film.fixMatch}
      </button>
      {open && (
        <Suspense fallback={null}>
          <FixMatchDialog mode={mode} onClose={() => setOpen(false)} onMatched={onMatched} />
        </Suspense>
      )}
    </>
  );
}
