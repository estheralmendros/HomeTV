import { describe, expect, it, vi } from 'vitest';
import { mediaState } from './media';

describe('HTMLMediaElement stub contract', () => {
  it('makes currentTime, duration, paused and error writable per element', () => {
    const a = document.createElement('video');
    const b = document.createElement('video');
    a.currentTime = 400;
    Object.assign(a, { duration: 1320 });
    expect(a.currentTime).toBe(400);
    expect(a.duration).toBe(1320);
    expect(b.currentTime).toBe(0);
    expect(Number.isNaN(b.duration)).toBe(true);
    expect(a.paused).toBe(true);
    Object.assign(a, { error: { code: 2 } });
    expect(a.error?.code).toBe(2);
  });

  it('play() resolves and unpauses; pause() pauses', async () => {
    const v = document.createElement('video');
    await expect(v.play()).resolves.toBeUndefined();
    expect(v.paused).toBe(false);
    v.pause();
    expect(v.paused).toBe(true);
  });

  it('play() can be made to reject with NotAllowedError', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'));
    const v = document.createElement('video');
    await expect(v.play()).rejects.toMatchObject({ name: 'NotAllowedError' });
  });

  it('load() resets like a browser and queues a timeupdate', async () => {
    const v = document.createElement('video');
    await v.play();
    v.currentTime = 400;
    Object.assign(v, { duration: 9000, error: { code: 3 } });
    const times: number[] = [];
    v.addEventListener('timeupdate', () => times.push(v.currentTime));
    v.load();
    expect(v.currentTime).toBe(0);
    expect(Number.isNaN(v.duration)).toBe(true);
    expect(v.paused).toBe(true);
    expect(v.error).toBeNull();
    expect(times).toEqual([]);
    await Promise.resolve();
    expect(times).toEqual([0]);
    expect(mediaState(v).currentTime).toBe(0);
  });

  it('stubs URL.createObjectURL / revokeObjectURL', () => {
    const url = URL.createObjectURL(new Blob(['x']));
    expect(url).toMatch(/^blob:/);
    URL.revokeObjectURL(url);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(url);
  });
});
