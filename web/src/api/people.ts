import { apiFetch } from './client';
import type { PersonDetail } from './types';

export const getPerson = (id: number, signal?: AbortSignal) => apiFetch<PersonDetail>(`/api/people/${id}`, { signal });
