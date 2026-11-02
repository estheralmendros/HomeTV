// HTMLMediaElement stubs. Installed by tests/setup.ts.
interface MediaStubState {
  currentTime: number;
  duration: number;
  paused: boolean;
  error: MediaError | { code: number; message?: string } | null;
}

const mediaStates = new WeakMap<HTMLMediaElement, MediaStubState>();

/** The stub state behind a media element's writable properties. */
export function mediaState(el: HTMLMediaElement): MediaStubState {
  let state = mediaStates.get(el);
  if (!state) {
    state = { currentTime: 0, duration: Number.NaN, paused: true, error: null };
    mediaStates.set(el, state);
  }
  return state;
}

function defineWritable<K extends keyof MediaStubState>(key: K) {
  Object.defineProperty(HTMLMediaElement.prototype, key, {
    configurable: true,
    get(this: HTMLMediaElement) {
      return mediaState(this)[key];
    },
    set(this: HTMLMediaElement, value: MediaStubState[K]) {
      mediaState(this)[key] = value;
    },
  });
}

defineWritable('currentTime');
defineWritable('duration');
defineWritable('paused');
defineWritable('error');

/** Default implementations. Tests override with vi.spyOn(HTMLMediaElement.prototype, 'play'). */
function play(this: HTMLMediaElement): Promise<void> {
  mediaState(this).paused = false;
  return Promise.resolve();
}

function pause(this: HTMLMediaElement): void {
  mediaState(this).paused = true;
}

/**
 * Like the browser's media element load algorithm: currentTime → 0, duration → NaN,
 * paused → true, error → null, then queue a `timeupdate` (as a microtask: deterministic with
 * fake timers). Rejecting a pending play() with AbortError is left to the code under test.
 */
function load(this: HTMLMediaElement): void {
  const state = mediaState(this);
  state.currentTime = 0;
  state.duration = Number.NaN;
  state.paused = true;
  state.error = null;
  queueMicrotask(() => this.dispatchEvent(new Event('timeupdate')));
}

/** jsdom has no TextTrack: each <track> element gets a fake `track` with a writable `mode`. */
/** Writable stand-in for a VTTCue's position fields (jsdom has no TextTrack/VTTCue). */
export interface FakeCue {
  line: number | 'auto';
  snapToLines: boolean;
}

export interface FakeTextTrack {
  mode: TextTrackMode;
  /** null until the track "loads"; tests fill it and dispatch `load` on the <track>. */
  cues: FakeCue[] | null;
}

const textTracks = new WeakMap<HTMLTrackElement, FakeTextTrack>();

export function fakeTextTrack(el: HTMLTrackElement): FakeTextTrack {
  let track = textTracks.get(el);
  if (!track) {
    track = { mode: 'disabled', cues: null };
    textTracks.set(el, track);
  }
  return track;
}

export function installMediaStubs() {
  HTMLMediaElement.prototype.play = play;
  HTMLMediaElement.prototype.pause = pause;
  HTMLMediaElement.prototype.load = load;
  Object.defineProperty(HTMLTrackElement.prototype, 'track', {
    configurable: true,
    get(this: HTMLTrackElement) {
      return fakeTextTrack(this);
    },
  });
}


