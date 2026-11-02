import { S } from '@/strings.es';

export interface ApiErrorInit {
  status: number;
  code: string;
  /** English envelope message: logs only, never shown (UI language: Server text). */
  message?: string;
  details?: Record<string, unknown>;
  /** Seconds, from Retry-After, else details.retry_after_seconds, else null. */
  retryAfter?: number | null;
}

/** A failed API call: non-2xx (error envelope) or a network failure (status 0). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;
  readonly retryAfter: number | null;

  constructor({ status, code, message, details = {}, retryAfter = null }: ApiErrorInit) {
    super(message ?? code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryAfter = retryAfter;
  }
}

export function isApiError(err: unknown, code?: string): err is ApiError {
  return err instanceof ApiError && (code === undefined || err.code === code);
}

/** The Spanish message for an error (S.errors); unknown codes get the generic catalog text. */
export function errorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return S.errors.unknown('unknown');
  const text = S.errors.byCode[err.code];
  if (text === undefined) return S.errors.unknown(err.code);
  return typeof text === 'function' ? text(err.retryAfter) : text;
}

/** Field names of a 422 validation_error (`details.errors[].loc`, last string element). */
export function validationFields(err: ApiError): string[] {
  const errors = err.details.errors;
  if (!Array.isArray(errors)) return [];
  return errors.flatMap((entry: unknown) => {
    const loc = (entry as { loc?: unknown } | null)?.loc;
    if (!Array.isArray(loc)) return [];
    const field = [...loc].reverse().find((part): part is string => typeof part === 'string');
    return field ? [field] : [];
  });
}

/** Diagnostics (English envelope text and details) go to the console only. */
export function logApiError(err: ApiError): void {
  console.warn(`[api] ${err.code} (${err.status}): ${err.message}`, err.details);
}
