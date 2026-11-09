import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as f from '../../tests/fixtures';
import {
  enqueueWrite,
  pendingChoice,
  rememberPick,
  resetTrackChoiceSession,
  setActiveProfile,
  setProfileGetter,
  settlePick,
} from './trackChoiceSession';

const english = f.trackChoice();
const spanish = f.trackChoice({ audio: f.audioChoice({ language: 'spa' }) });

/** A send() the test settles by hand. */
function gate() {
  let resolve: () => void = () => undefined;
  let reject: (err: unknown) => void = () => undefined;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  const send = vi.fn(() => promise);
  return { send, resolve, reject };
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

let profile: number | null = 1;
beforeEach(() => {
  profile = 1;
  setProfileGetter(() => profile);
  setActiveProfile(1);
});
afterEach(() => resetTrackChoiceSession());

describe('overrides', () => {
  it('a pick is pending until a settle with its own seq', () => {
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
    const seq = rememberPick(1, 'film', 12, english);
    expect(pendingChoice(1, 'film', 12)).toEqual(english);
    settlePick(1, 'film', 12, seq, true);
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
  });

  it('an older settle never drops a newer pick', () => {
    const first = rememberPick(1, 'show', 7, english);
    const second = rememberPick(1, 'show', 7, spanish);
    expect(second).toBeGreaterThan(first);
    settlePick(1, 'show', 7, first, true);
    expect(pendingChoice(1, 'show', 7)).toEqual(spanish);
  });

  it('a failed settle keeps the pick for the rest of the tab', () => {
    const seq = rememberPick(1, 'film', 12, english);
    settlePick(1, 'film', 12, seq, false);
    expect(pendingChoice(1, 'film', 12)).toEqual(english);
  });

  it('is keyed by profile and title', () => {
    rememberPick(1, 'film', 12, english);
    expect(pendingChoice(2, 'film', 12)).toBeUndefined();
    expect(pendingChoice(1, 'show', 12)).toBeUndefined();
    expect(pendingChoice(1, 'film', 13)).toBeUndefined();
  });

  it('setActiveProfile with another id (or null) clears every override; the same id keeps them', () => {
    rememberPick(1, 'film', 12, english);
    setActiveProfile(1);
    expect(pendingChoice(1, 'film', 12)).toEqual(english);
    setActiveProfile(2);
    expect(pendingChoice(1, 'film', 12)).toBeUndefined();
    rememberPick(2, 'film', 12, english);
    setActiveProfile(null);
    expect(pendingChoice(2, 'film', 12)).toBeUndefined();
  });
});

describe('enqueueWrite', () => {
  it('sends at once and resolves ok', async () => {
    const seq = rememberPick(1, 'film', 12, english);
    const send = vi.fn(() => Promise.resolve());
    await expect(enqueueWrite(1, 'film', 12, seq, send)).resolves.toEqual({ outcome: 'ok' });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('a failure resolves failed with the error', async () => {
    const err = new Error('boom');
    const result = await enqueueWrite(1, 'film', 12, 1, () => Promise.reject(err));
    expect(result).toEqual({ outcome: 'failed', error: err });
  });

  it('one in flight per title; a newer wish replaces the queued one (superseded) and goes after the settle', async () => {
    const a = gate();
    const b = gate();
    const c = gate();
    const first = enqueueWrite(1, 'show', 7, 1, a.send);
    const second = enqueueWrite(1, 'show', 7, 2, b.send);
    const third = enqueueWrite(1, 'show', 7, 3, c.send);
    expect(a.send).toHaveBeenCalledTimes(1);
    expect(b.send).not.toHaveBeenCalled();
    await expect(second).resolves.toEqual({ outcome: 'superseded' });
    expect(c.send).not.toHaveBeenCalled();
    a.resolve();
    await expect(first).resolves.toEqual({ outcome: 'ok' });
    await flush();
    expect(c.send).toHaveBeenCalledTimes(1);
    c.resolve();
    await expect(third).resolves.toEqual({ outcome: 'ok' });
    expect(b.send).not.toHaveBeenCalled();
  });

  it('other titles are independent', () => {
    const a = gate();
    const b = gate();
    void enqueueWrite(1, 'show', 7, 1, a.send);
    void enqueueWrite(1, 'film', 7, 2, b.send);
    expect(a.send).toHaveBeenCalledTimes(1);
    expect(b.send).toHaveBeenCalledTimes(1);
  });

  it('a profile change drops the queued wish (never sent) and ignores the in-flight settle', async () => {
    const a = gate();
    const b = gate();
    const first = enqueueWrite(1, 'show', 7, 1, a.send);
    const queued = enqueueWrite(1, 'show', 7, 2, b.send);
    profile = 2;
    setActiveProfile(2);
    await expect(queued).resolves.toEqual({ outcome: 'dropped' });
    a.resolve();
    await expect(first).resolves.toEqual({ outcome: 'dropped' });
    await flush();
    expect(b.send).not.toHaveBeenCalled();
  });

  it('a wish whose profile is not the getter’s at send time is dropped, send never called', async () => {
    profile = 2;
    const send = vi.fn(() => Promise.resolve());
    await expect(enqueueWrite(1, 'film', 12, 1, send)).resolves.toEqual({ outcome: 'dropped' });
    profile = null;
    await expect(enqueueWrite(1, 'film', 12, 2, send)).resolves.toEqual({ outcome: 'dropped' });
    expect(send).not.toHaveBeenCalled();
  });

  it('the queued wish is checked against the getter when it is sent', async () => {
    const a = gate();
    const b = gate();
    void enqueueWrite(1, 'show', 7, 1, a.send);
    const queued = enqueueWrite(1, 'show', 7, 2, b.send);
    profile = null; // signed out in another way (the getter answers null)
    a.resolve();
    await expect(queued).resolves.toEqual({ outcome: 'dropped' });
    expect(b.send).not.toHaveBeenCalled();
  });

  it('unregistered getter (after reset): every wish is dropped', async () => {
    resetTrackChoiceSession();
    const send = vi.fn(() => Promise.resolve());
    await expect(enqueueWrite(1, 'film', 12, 1, send)).resolves.toEqual({ outcome: 'dropped' });
    expect(send).not.toHaveBeenCalled();
  });
});
