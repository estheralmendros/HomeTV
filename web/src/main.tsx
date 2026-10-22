import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { createQueryClient } from '@/api/queryClient';
import { AppProviders } from '@/AppProviders';
import { registerChunkReload } from '@/lib/chunkReload';
import { ROUTER_PROVIDER_FUTURE, createAppRouter } from '@/router';
import '@/styles/global.css';

registerChunkReload(window);

const queryClient = createQueryClient();
const router = createAppRouter();
const root = document.getElementById('root');
if (!root) throw new Error('#root missing in index.html');

createRoot(root).render(
  <StrictMode>
    <AppProviders queryClient={queryClient} navigate={(to) => void router.navigate(to)}>
      <RouterProvider router={router} future={ROUTER_PROVIDER_FUTURE} />
    </AppProviders>
  </StrictMode>,
);
