import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AdminPinProvider } from '@/auth/AdminPinProvider';
import { AuthProvider } from '@/auth/AuthProvider';
import { ToastProvider } from '@/components/Toast';
import type { SubscribeLocation } from '@/features/ratings/ratingPrompt';
import { RatingPromptHost } from '@/features/ratings/RatingPromptHost';

export interface AppProvidersProps {
  queryClient: QueryClient;
  /** The data router's navigate (providers sit outside RouterProvider). */
  navigate: (to: string) => void;
  /** Committed router location changes (the rating prompt host closes its dialog on navigation). */
  subscribeLocation?: SubscribeLocation;
  children: ReactNode;
}

/** Provider stack shared by main.tsx and tests/renderWithProviders. */
export function AppProviders({ queryClient, navigate, subscribeLocation, children }: AppProvidersProps) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthProvider navigate={navigate}>
          <RatingPromptHost subscribeLocation={subscribeLocation}>
            <AdminPinProvider>{children}</AdminPinProvider>
          </RatingPromptHost>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
