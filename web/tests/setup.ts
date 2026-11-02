// Global test setup:
// - jest-dom matchers, RTL cleanup, MSW lifecycle with onUnhandledRequest: 'error' (no network);
// - HTMLMediaElement stubs: play/pause/load, writable currentTime/duration/paused/error, and a
//   load() that resets like a browser's media element load algorithm;
// - URL.createObjectURL / revokeObjectURL stubs (fresh vi.fn per test);
// - per-test reset of storage and of module state (api client hooks, admin unlock);
// - client capabilities detected from a desktop Chrome env (tests/capsEnvs.ts; no HEVC). Switch
//   with setCapabilityEnv(iphoneEnv()) etc.; reset before each test;
// - window.matchMedia with a desktop viewport (tests/viewport.ts); setViewportWidth(360) for phones.
import '@testing-library/jest-dom/vitest';
import { notifyManager } from '@tanstack/react-query';
import { cleanup } from '@testing-library/react';
import { transferableAbortController } from 'node:util';
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest';
import { resetApiClient } from '@/api/client';
import { adminUnlock } from '@/auth/adminUnlock';
import { historyIndex } from '@/lib/historyIndex';
import { setCapabilityEnv } from '@/player/capabilities';
import { resetProgressSync } from '@/player/progressSync';
import { chromeEnv } from './capsEnvs';
import { FakeHls } from './fakeHls';
import { installMediaStubs } from './media';
import { disposeRecorders, server } from './msw/server';
import { installMatchMedia } from './viewport';

// --- MSW -----------------------------------------------------------------------------------

// --- AbortSignal bridge ---------------------------------------------------------------------
// jsdom's AbortSignal is not the Node AbortSignal that Node's Request/fetch accept (React
// Router's data router and React Query both pass signals). Convert them into Node signals that
// follow the jsdom ones, so aborts still abort.

function toNodeSignal(signal: AbortSignal | null | undefined): AbortSignal | undefined {
  if (!signal) return undefined;
  const controller = transferableAbortController();
  if (signal.aborted) controller.abort(signal.reason);
  else signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
  return controller.signal;
}

function withNodeSignal(init: RequestInit | undefined): RequestInit | undefined {
  return init?.signal ? { ...init, signal: toNodeSignal(init.signal) } : init;
}

const NodeRequest = globalThis.Request;
globalThis.Request = class Request extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(input, withNodeSignal(init));
  }
} as typeof globalThis.Request;

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
  // Outermost wrapper (after MSW patched fetch).
  const inner = globalThis.fetch;
  globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit) => inner(input, withNodeSignal(init));
});
afterEach(() => {
  disposeRecorders();
  server.resetHandlers();
});
afterAll(() => server.close());

// --- HTMLMediaElement (tests/media.ts) ------------------------------------------------

installMediaStubs();

// --- URL blob stubs ------------------------------------------------------------------------

let blobCounter = 0;

beforeEach(() => {
  setCapabilityEnv(chromeEnv());
  installMatchMedia();
  blobCounter = 0;
  URL.createObjectURL = vi.fn(() => `blob:http://localhost:3000/mock-${++blobCounter}`);
  URL.revokeObjectURL = vi.fn();
});

// --- Per-test reset ------------------------------------------------------------------------

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  installMediaStubs();
  vi.useRealTimers();
  notifyManager.setScheduler((callback) => setTimeout(callback, 0));
  try {
    window.localStorage.clear();
    window.sessionStorage.clear();
  } catch {
    // storage stubbed to throw by a test
  }
  resetApiClient();
  adminUnlock.clear();
  historyIndex.read = () => historyIndex.browser();
  resetProgressSync();
  FakeHls.reset();
  document.title = 'HomeTV';
});
