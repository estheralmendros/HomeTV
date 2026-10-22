import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);

export interface RecordedRequest {
  method: string;
  url: URL;
  headers: Headers;
  /** `fetch(..., { keepalive: true })` (unload/final reports, exit DELETE). */
  keepalive: boolean;
  /** Parsed JSON body, raw text, or null when empty. */
  body: unknown;
}

/**
 * Records every request MSW sees from now on (in order). `calls` fills asynchronously because
 * bodies are read from a clone; await `settled()` before asserting on bodies.
 */
const activeRecorders = new Set<() => void>();

/** Disposes every recorder still listening (called by tests/setup.ts after each test). */
export function disposeRecorders(): void {
  for (const dispose of [...activeRecorders]) dispose();
}

export function recordRequests() {
  const calls: RecordedRequest[] = [];
  const pending: Array<Promise<void>> = [];
  const listener = ({ request }: { request: Request }) => {
    const entry: RecordedRequest = {
      method: request.method,
      url: new URL(request.url),
      headers: request.headers,
      keepalive: request.keepalive,
      body: null,
    };
    calls.push(entry);
    pending.push(
      request
        .clone()
        .text()
        .then((text) => {
          if (!text) return;
          try {
            entry.body = JSON.parse(text);
          } catch {
            entry.body = text;
          }
        })
        .catch(() => undefined),
    );
  };
  server.events.on('request:start', listener);
  const dispose = () => {
    activeRecorders.delete(dispose);
    server.events.removeListener('request:start', listener);
  };
  activeRecorders.add(dispose);
  return {
    calls,
    /** Requests whose method and pathname match. */
    find(method: string, pathname: string | RegExp): RecordedRequest[] {
      return calls.filter(
        (c) =>
          c.method === method.toUpperCase() &&
          (typeof pathname === 'string' ? c.url.pathname === pathname : pathname.test(c.url.pathname)),
      );
    },
    settled: () => Promise.all(pending).then(() => undefined),
    dispose,
  };
}
