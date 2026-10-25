// navigator.mediaSession / MediaMetadata stubs (jsdom has neither). `stubMediaSession()` installs
// a recorder: `handlers` holds the registered action handlers (deleted on `null`), `metadata`
// and `playbackState` are plain writable fields. The returned `restore()` removes both globals.
import { act } from '@testing-library/react';

export class FakeMediaMetadata {
  title: string;
  artist: string;
  album: string;
  artwork: MediaImage[];
  constructor(init: MediaMetadataInit = {}) {
    this.title = init.title ?? '';
    this.artist = init.artist ?? '';
    this.album = init.album ?? '';
    this.artwork = [...(init.artwork ?? [])];
  }
}

export interface FakeMediaSession {
  metadata: FakeMediaMetadata | null;
  playbackState: MediaSessionPlaybackState;
  handlers: Map<MediaSessionAction, MediaSessionActionHandler>;
  /** Every setActionHandler call, in order (null = cleared). */
  calls: Array<[MediaSessionAction, MediaSessionActionHandler | null]>;
  setActionHandler(action: MediaSessionAction, handler: MediaSessionActionHandler | null): void;
  /** Invokes a registered handler inside act() (as the browser does on a media key). */
  trigger(action: MediaSessionAction, details?: Partial<MediaSessionActionDetails>): void;
  restore(): void;
}

export function stubMediaSession(): FakeMediaSession {
  const session: FakeMediaSession = {
    metadata: null,
    playbackState: 'none',
    handlers: new Map(),
    calls: [],
    setActionHandler(action, handler) {
      session.calls.push([action, handler]);
      if (handler) session.handlers.set(action, handler);
      else session.handlers.delete(action);
    },
    trigger(action, details = {}) {
      const handler = session.handlers.get(action);
      if (!handler) throw new Error(`no mediaSession handler for "${action}"`);
      act(() => handler({ action, ...details } as MediaSessionActionDetails));
    },
    restore() {
      delete (navigator as { mediaSession?: unknown }).mediaSession;
      delete (globalThis as { MediaMetadata?: unknown }).MediaMetadata;
    },
  };
  Object.defineProperty(navigator, 'mediaSession', { configurable: true, value: session });
  (globalThis as { MediaMetadata?: unknown }).MediaMetadata = FakeMediaMetadata;
  return session;
}
