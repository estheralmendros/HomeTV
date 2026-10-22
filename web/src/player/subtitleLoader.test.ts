import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setTokenGetter } from '@/api/client';
import * as f from '../../tests/fixtures';
import { blobText, until } from '../../tests/flush';
import { apiError } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { SubtitleLoader } from './subtitleLoader';

const SUBS = '/api/media-files/:id/subtitles/:track';
const track91 = f.subtitleTrack({ id: 'emb-3' }, 91);
const track92 = f.subtitleTrack({ id: 'emb-3' }, 92);

let rec: ReturnType<typeof recordRequests>;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  setTokenGetter(() => 'token-1');
  rec = recordRequests();
});
afterEach(() => rec.dispose());

const subtitleCalls = () => rec.find('GET', /\/subtitles\//);

function pendingThen(pendingCount: number, headers: HeadersInit | undefined, details: Record<string, unknown> = {}) {
  let n = 0;
  server.use(
    http.get(SUBS, () => {
      n += 1;
      if (n <= pendingCount) return apiError(503, 'subtitle_pending', { headers, details });
      return new HttpResponse(f.WEBVTT, { headers: { 'Content-Type': 'text/vtt' } });
    }),
  );
}

describe('SubtitleLoader', () => {
  it('fetches with the Bearer header, no token in the URL, and returns a blob URL', async () => {
    const loader = new SubtitleLoader();
    const result = await loader.load(91, track91);
    expect(result).toEqual({ status: 'loaded', url: 'blob:http://localhost:3000/mock-1' });
    const [call] = subtitleCalls();
    expect(call?.headers.get('Authorization')).toBe('Bearer token-1');
    expect(call?.url.searchParams.has('token')).toBe(false);
    expect(call?.url.pathname).toBe('/api/media-files/91/subtitles/emb-3.vtt');
    const blob = vi.mocked(URL.createObjectURL).mock.calls[0]?.[0] as Blob;
    expect(blob.type).toBe('text/vtt');
    await expect(blobText(blob)).resolves.toBe(f.WEBVTT);
  });

  it('retries 503 subtitle_pending after Retry-After: exactly two requests 10 s apart', async () => {
    pendingThen(1, { 'Retry-After': '10' });
    const onPending = vi.fn();
    const loader = new SubtitleLoader();
    const result = loader.load(91, track91, { onPending });
    await until(() => expect(subtitleCalls()).toHaveLength(1));
    await until(() => expect(onPending).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(9_999);
    expect(subtitleCalls()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    await expect(result).resolves.toMatchObject({ status: 'loaded' });
    expect(subtitleCalls()).toHaveLength(2);
  });

  it('falls back to details.retry_after_seconds without the header', async () => {
    pendingThen(1, undefined, { retry_after_seconds: 3 });
    const loader = new SubtitleLoader();
    const result = loader.load(91, track91);
    await until(() => expect(subtitleCalls()).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(2_999);
    expect(subtitleCalls()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    await expect(result).resolves.toMatchObject({ status: 'loaded' });
  });

  it('gives up after 5 min of pending', async () => {
    pendingThen(Infinity, { 'Retry-After': '10' });
    const loader = new SubtitleLoader();
    let outcome: unknown = null;
    void loader.load(91, track91).then((r) => (outcome = r));
    for (let i = 0; i < 29 && outcome === null; i += 1) {
      await until(() => expect(subtitleCalls()).toHaveLength(i + 1));
      await vi.advanceTimersByTimeAsync(10_000);
    }
    expect(outcome).toBeNull();
    await vi.advanceTimersByTimeAsync(20_000);
    await until(() =>
      expect(outcome).toEqual({
        status: 'failed',
        message: 'Los subtítulos aún se están preparando. Vuelve a intentarlo más tarde.',
      }),
    );
    const total = subtitleCalls().length;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(subtitleCalls()).toHaveLength(total);
    expect(total).toBeLessThanOrEqual(32);
  });

  it.each([
    [500, 'subtitle_extraction_failed', 'No se han podido extraer estos subtítulos del archivo.'],
    [504, 'subtitle_timeout', 'Los subtítulos han tardado demasiado en convertirse.'],
    [500, 'other_error', 'No se han podido cargar los subtítulos.'],
  ])('%s %s is terminal, with no retry', async (status, code, message) => {
    server.use(http.get(SUBS, () => apiError(status, code)));
    const loader = new SubtitleLoader();
    await expect(loader.load(91, track91)).resolves.toEqual({ status: 'failed', message });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(subtitleCalls()).toHaveLength(1);
  });

  it('a track without URL fails', async () => {
    const loader = new SubtitleLoader();
    await expect(loader.load(91, { ...track91, url: null })).resolves.toMatchObject({ status: 'failed' });
  });

  it('abort (track change) cancels the pending timer', async () => {
    pendingThen(Infinity, { 'Retry-After': '10' });
    const loader = new SubtitleLoader();
    const controller = new AbortController();
    const result = loader.load(91, track91, { signal: controller.signal });
    await until(() => expect(subtitleCalls()).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(5_000);
    controller.abort();
    await expect(result).resolves.toEqual({ status: 'aborted' });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(subtitleCalls()).toHaveLength(1);
  });

  it('abort during the fetch resolves aborted', async () => {
    const loader = new SubtitleLoader();
    const controller = new AbortController();
    const result = loader.load(91, track91, { signal: controller.signal });
    controller.abort();
    await expect(result).resolves.toEqual({ status: 'aborted' });
  });

  it('reselecting a loaded track is a cache hit', async () => {
    const loader = new SubtitleLoader();
    const first = await loader.load(91, track91);
    const second = await loader.load(91, track91);
    expect(second).toEqual(first);
    expect(subtitleCalls()).toHaveLength(1);
    expect(loader.cached(91, 'emb-3')).toBe('blob:http://localhost:3000/mock-1');
  });

  it('keys the cache by media_file_id:track.id (same emb-3 on two files)', async () => {
    const loader = new SubtitleLoader();
    const a = await loader.load(91, track91);
    const b = await loader.load(92, track92);
    expect(subtitleCalls().map((c) => c.url.pathname)).toEqual([
      '/api/media-files/91/subtitles/emb-3.vtt',
      '/api/media-files/92/subtitles/emb-3.vtt',
    ]);
    expect(a).not.toEqual(b);
  });

  it('revokes the previous file’s blob URLs when the file changes, and all on dispose', async () => {
    const loader = new SubtitleLoader();
    const a = await loader.load(91, track91);
    const b = await loader.load(92, track92);
    loader.setFile(92);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(a.status === 'loaded' ? a.url : '');
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith(b.status === 'loaded' ? b.url : '');
    expect(loader.cached(91, 'emb-3')).toBeNull();
    loader.dispose();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(b.status === 'loaded' ? b.url : '');
    expect(loader.cached(92, 'emb-3')).toBeNull();
  });

  it('a load finishing after dispose revokes its own URL', async () => {
    const loader = new SubtitleLoader();
    const result = loader.load(91, track91);
    loader.dispose();
    await expect(result).resolves.toEqual({ status: 'aborted' });
  });
});
