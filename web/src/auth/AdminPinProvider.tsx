// Hosts the single AdminPinModal. `requestUnlock()` opens it (or joins the open request) and
// resolves true after a successful unlock, false on cancel or 403.
import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { queryKeys } from '@/api/queryKeys';
import { useToast } from '@/components/Toast';
import { S } from '@/strings.es';
import { AdminPinModal } from './AdminPinModal';

interface PendingUnlock {
  purpose?: string;
  promise: Promise<boolean>;
  resolve: (unlocked: boolean) => void;
}

interface AdminPinContextValue {
  requestUnlock: (purpose?: string) => Promise<boolean>;
}

const AdminPinContext = createContext<AdminPinContextValue | null>(null);

export function AdminPinProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const pendingRef = useRef<PendingUnlock | null>(null);
  const [pending, setPending] = useState<PendingUnlock | null>(null);

  const requestUnlock = useCallback((purpose?: string) => {
    if (pendingRef.current) return pendingRef.current.promise;
    let resolve!: (unlocked: boolean) => void;
    const promise = new Promise<boolean>((r) => {
      resolve = r;
    });
    const entry: PendingUnlock = { purpose, promise, resolve };
    pendingRef.current = entry;
    setPending(entry);
    return promise;
  }, []);

  const settle = useCallback((unlocked: boolean) => {
    const entry = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    entry?.resolve(unlocked);
  }, []);

  const onForbidden = useCallback(() => {
    settle(false);
    void queryClient.invalidateQueries({ queryKey: queryKeys.me() });
    toast.show(S.adminPin.forbidden);
  }, [queryClient, settle, toast]);

  const value = useMemo(() => ({ requestUnlock }), [requestUnlock]);

  return (
    <AdminPinContext.Provider value={value}>
      {children}
      {pending && (
        <AdminPinModal
          purpose={pending.purpose}
          onUnlocked={() => settle(true)}
          onCancel={() => settle(false)}
          onForbidden={onForbidden}
        />
      )}
    </AdminPinContext.Provider>
  );
}

export function useRequestAdminUnlock(): (purpose?: string) => Promise<boolean> {
  const value = useContext(AdminPinContext);
  if (!value) throw new Error('useRequestAdminUnlock must be used inside <AdminPinProvider>');
  return value.requestUnlock;
}
