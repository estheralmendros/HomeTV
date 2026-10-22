// Waiting helpers for fake-timer tests. `vi.waitFor` advances fake time on every check, which
// breaks exact timing assertions; these yield REAL macrotasks (MSW/fetch need them) without
// moving the fake clock. Captured at import time, before any test installs fake timers.
import { vi } from 'vitest';

const realSetImmediate = globalThis.setImmediate;

export const tick = () => new Promise<void>((resolve) => realSetImmediate(resolve));

/** Yields real macrotasks until `check` stops throwing (default ≤ 500 turns). */
export async function until(check: () => void, maxTurns = 500): Promise<void> {
  let lastError: unknown;
  for (let i = 0; i < maxTurns; i += 1) {
    try {
      check();
      return;
    } catch (err) {
      lastError = err;
      await tick();
    }
  }
  throw lastError;
}

/** Text of a Blob through FileReader (jsdom's Blob has no `.text()`). */
export function blobText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

/**
 * Advances fake timers, then yields a few real macrotasks so pending fetches and React renders
 * settle. No async act(): Vitest runs without globals, so RTL leaves IS_REACT_ACT_ENVIRONMENT
 * unset and updates outside act are processed normally (an async act scope races with fetch
 * continuations and warns).
 */
export async function advance(ms: number, settleTurns = 5): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
  for (let i = 0; i < settleTurns; i += 1) await tick();
}
