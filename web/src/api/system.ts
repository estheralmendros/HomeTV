import { apiFetch } from './client';
import type { Info } from './types';

export const getInfo = (signal?: AbortSignal) => apiFetch<Info>('/api/info', { auth: false, signal });
