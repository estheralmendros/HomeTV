// Fake hls.js for `vi.mock('hls.js', () => import('<rel>/tests/fakeHls'))` (prd2 § Testing
// Decisions): records constructor config, loadSource/attachMedia/destroy/recoverMediaError calls
// in a shared, ordered `hlsLog` (to assert "old destroy before new attachMedia"), and exposes
// `emit(Hls.Events.ERROR, data)`. `destroy()` detaches like hls.js does: it clears the element's
// `src` and calls `load()` (the media stub resets currentTime and queues a `timeupdate`).
// tests/setup.ts calls `FakeHls.reset()` after every test.

type Handler = (event: string, data: unknown) => void;

export const Events = {
  MEDIA_ATTACHED: 'hlsMediaAttached',
  MANIFEST_PARSED: 'hlsManifestParsed',
  ERROR: 'hlsError',
} as const;

export const ErrorTypes = {
  NETWORK_ERROR: 'networkError',
  MEDIA_ERROR: 'mediaError',
  MUX_ERROR: 'muxError',
  OTHER_ERROR: 'otherError',
} as const;

export const ErrorDetails = {
  FRAG_LOAD_ERROR: 'fragLoadError',
  MANIFEST_LOAD_ERROR: 'manifestLoadError',
  BUFFER_STALLED_ERROR: 'bufferStalledError',
} as const;

/** Every fake call in order, e.g. ["new#1", "loadSource#1", "attachMedia#1", "destroy#1", …]. */
export const hlsLog: string[] = [];

export class FakeHls {
  static Events = Events;
  static ErrorTypes = ErrorTypes;
  static ErrorDetails = ErrorDetails;
  static instances: FakeHls[] = [];
  private static nextId = 1;

  static isSupported(): boolean {
    return true;
  }

  /** The most recently constructed instance. */
  static get last(): FakeHls | undefined {
    return FakeHls.instances[FakeHls.instances.length - 1];
  }

  static reset(): void {
    FakeHls.instances = [];
    FakeHls.nextId = 1;
    hlsLog.length = 0;
  }

  readonly id: number;
  readonly config: Record<string, unknown>;
  url: string | null = null;
  media: HTMLMediaElement | null = null;
  destroyed = false;
  recoverMediaErrorCalls = 0;
  private handlers = new Map<string, Set<Handler>>();

  constructor(config: Record<string, unknown> = {}) {
    this.id = FakeHls.nextId++;
    this.config = config;
    FakeHls.instances.push(this);
    hlsLog.push(`new#${this.id}`);
  }

  on(event: string, handler: Handler): void {
    let set = this.handlers.get(event);
    if (!set) this.handlers.set(event, (set = new Set()));
    set.add(handler);
  }

  off(event: string, handler: Handler): void {
    this.handlers.get(event)?.delete(handler);
  }

  loadSource(url: string): void {
    this.url = url;
    hlsLog.push(`loadSource#${this.id}`);
  }

  attachMedia(media: HTMLMediaElement): void {
    this.media = media;
    hlsLog.push(`attachMedia#${this.id}`);
  }

  detachMedia(): void {
    const media = this.media;
    this.media = null;
    if (media) {
      media.removeAttribute('src');
      media.load();
    }
  }

  recoverMediaError(): void {
    this.recoverMediaErrorCalls += 1;
    hlsLog.push(`recoverMediaError#${this.id}`);
  }

  destroy(): void {
    hlsLog.push(`destroy#${this.id}`);
    this.detachMedia();
    this.destroyed = true;
    this.handlers.clear();
  }

  /** Test helper: fire an hls.js event at the registered handlers. */
  emit(event: string, data: unknown = {}): void {
    for (const handler of [...(this.handlers.get(event) ?? [])]) handler(event, data);
  }

  /** Test helper: a fatal hls.js error. */
  fatal(type: string = ErrorTypes.NETWORK_ERROR, details: string = ErrorDetails.FRAG_LOAD_ERROR, extra: Record<string, unknown> = {}): void {
    this.emit(Events.ERROR, { type, details, fatal: true, ...extra });
  }
}

export default FakeHls;
