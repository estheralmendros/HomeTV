// Tab-lifetime session state of the remembered track choice (prd.md § Shared Definitions: Save,
// Session precedence):
// - overrides: picks not yet confirmed by the server (and refetched), per (profile, title). They win
//   over fetched data until `settlePick(ok)` with the latest seq; a failed PUT keeps them for the tab;
// - the per-title write queue: one PUT in flight per (profile, title); a newer wish replaces the
//   queued one ('superseded') and is sent when the in-flight one settles (latest wins);
// - profile safety: a queued wish is sent only while the profile getter (registered by AuthProvider
//   in its first render) answers the wish's profile, else it resolves 'dropped' unsent. A profile
//   change made by AuthProvider (`setActiveProfile`) clears the overrides, drops the queued wishes
//   and makes the in-flight PUT's settle resolve 'dropped' (its token was the old profile's).
// Plain module state (like progressSync); `resetTrackChoiceSession()` runs after each test.
import type { TitleKind, TrackChoice } from '@/api/types';

export type WriteOutcome = 'ok' | 'failed' | 'superseded' | 'dropped';

/** The settle of one wish; `error` is the send's rejection for 'failed'. */
export interface WriteResult {
  outcome: WriteOutcome;
  error?: unknown;
}

interface Wish {
  profileId: number;
  send: () => Promise<void>;
  resolve: (result: WriteResult) => void;
}

interface Queue {
  inFlight: boolean;
  queued: Wish | null;
}

const overrides = new Map<string, { choice: TrackChoice; seq: number }>();
const queues = new Map<string, Queue>();
let lastSeq = 0;
let epoch = 0;
let activeProfile: number | null = null;
let profileGetter: () => number | null = () => null;
/** Module-lifetime listeners of a profile change (useSubtitleStyleSave's queue); never reset. */
const profileChangeListeners = new Set<() => void>();

const keyOf = (profileId: number, kind: TitleKind, id: number) => `${profileId}:${kind}:${id}`;
const titleKeyOf = (kind: TitleKind, id: number) => `${kind}:${id}`;

export function pendingChoice(profileId: number, kind: TitleKind, id: number): TrackChoice | undefined {
  return overrides.get(keyOf(profileId, kind, id))?.choice;
}

/** Records a pick as the session override; returns its seq. */
export function rememberPick(profileId: number, kind: TitleKind, id: number, choice: TrackChoice): number {
  lastSeq += 1;
  overrides.set(keyOf(profileId, kind, id), { choice, seq: lastSeq });
  return lastSeq;
}

/** ok: drops the override when `seq` is still the latest; failure: keeps it for the tab. */
export function settlePick(profileId: number, kind: TitleKind, id: number, seq: number, ok: boolean): void {
  if (!ok) return;
  const key = keyOf(profileId, kind, id);
  if (overrides.get(key)?.seq === seq) overrides.delete(key);
}

function dropQueued(): void {
  for (const queue of queues.values()) {
    queue.queued?.resolve({ outcome: 'dropped' });
    queue.queued = null;
  }
}

function runNext(key: string, queue: Queue): void {
  const wish = queue.queued;
  queue.queued = null;
  if (wish) send(key, queue, wish);
  else if (queues.get(key) === queue) queues.delete(key);
}

function send(key: string, queue: Queue, wish: Wish): void {
  if (wish.profileId !== profileGetter()) {
    wish.resolve({ outcome: 'dropped' });
    runNext(key, queue);
    return;
  }
  queue.inFlight = true;
  const startedIn = epoch;
  wish.send().then(
    () => wish.resolve({ outcome: startedIn === epoch ? 'ok' : 'dropped' }),
    (error: unknown) => wish.resolve(startedIn === epoch ? { outcome: 'failed', error } : { outcome: 'dropped' }),
  ).finally(() => {
    queue.inFlight = false;
    runNext(key, queue);
  });
}

/**
 * Serialized PUT per (profile, title): one in flight; a newer wish replaces the queued one and is
 * sent when the in-flight one settles. Resolves per wish: 'ok' / 'failed' (with the error) for a
 * sent wish, 'superseded' when a newer wish replaced it, 'dropped' when it was never sent (another
 * profile at send time, a profile change) or its settle came after a profile change.
 */
export function enqueueWrite(
  profileId: number,
  kind: TitleKind,
  id: number,
  sendFn: () => Promise<void>,
): Promise<WriteResult> {
  const key = `${profileId}:${titleKeyOf(kind, id)}`;
  let queue = queues.get(key);
  if (!queue) {
    queue = { inFlight: false, queued: null };
    queues.set(key, queue);
  }
  const target = queue;
  return new Promise<WriteResult>((resolve) => {
    const wish: Wish = { profileId, send: sendFn, resolve };
    if (target.inFlight) {
      target.queued?.resolve({ outcome: 'superseded' });
      target.queued = wish;
    } else {
      send(key, target, wish);
    }
  });
}

/**
 * Registered once by AuthProvider during its first render (next to setTokenGetter): reads the
 * session ref, so it is right after a reload, a cross-tab change or any setSession. The send-time
 * check reads it. Default (unregistered): () => null.
 */
export function setProfileGetter(getter: () => number | null): void {
  profileGetter = getter;
}

/** The session profile as the registered getter answers it now (null when unregistered). */
export function currentProfileId(): number | null {
  return profileGetter();
}

/** Runs `listener` on every profile change `setActiveProfile` applies; returns the unsubscribe. */
export function onActiveProfileChange(listener: () => void): () => void {
  profileChangeListeners.add(listener);
  return () => profileChangeListeners.delete(listener);
}

/**
 * Called by AuthProvider on a profile change it makes (signIn, endSession): a different id (or
 * null) clears every override and drops every queued (unsent) wish as 'dropped'.
 */
export function setActiveProfile(profileId: number | null): void {
  if (profileId !== null && profileId === activeProfile) return;
  activeProfile = profileId;
  epoch += 1;
  overrides.clear();
  dropQueued();
  for (const listener of profileChangeListeners) listener();
}

/** Tests (tests/setup.ts afterEach): clear + active profile null + unregistered getter. */
export function resetTrackChoiceSession(): void {
  activeProfile = null;
  epoch += 1;
  overrides.clear();
  dropQueued();
  queues.clear();
  profileGetter = () => null;
}
