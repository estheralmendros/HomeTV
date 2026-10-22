import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AdminPinProvider } from '@/auth/AdminPinProvider';
import { AuthProvider } from '@/auth/AuthProvider';
import { ToastProvider } from '@/components/Toast';

export interface AppProvidersProps {
  queryClient: QueryClient;
  /** The data router's navigate (providers sit outside RouterProvider). */
  navigate: (to: string) => void;
  children: ReactNode;
}

/** Provider stack shared by main.tsx and tests/renderWithProviders. */
export function AppProviders({ queryClient, navigate, children }: AppProvidersProps) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthProvider navigate={navigate}>
          <AdminPinProvider>{children}</AdminPinProvider>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
