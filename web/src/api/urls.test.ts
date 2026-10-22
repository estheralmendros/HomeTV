import { describe, expect, it } from 'vitest';
import { setTokenGetter } from './client';
import { artworkUrl, withToken } from './urls';

describe('withToken', () => {
  it('appends the encoded token with ? or &', () => {
    setTokenGetter(() => 'a b+c');
    expect(withToken('/api/media-files/91/stream')).toBe('/api/media-files/91/stream?token=a%20b%2Bc');
    expect(withToken('/api/artwork/w342/x.jpg?v=2')).toBe('/api/artwork/w342/x.jpg?v=2&token=a%20b%2Bc');
  });

  it('leaves the URL unchanged without a token', () => {
    setTokenGetter(() => null);
    expect(withToken('/api/artwork/w342/x.jpg')).toBe('/api/artwork/w342/x.jpg');
  });
});

describe('artworkUrl', () => {
  it('returns the tokenized size, or null for a missing image', () => {
    setTokenGetter(() => 't');
    const img = { small: '/api/artwork/w342/p.jpg', large: '/api/artwork/w780/p.jpg' };
    expect(artworkUrl(img, 'small')).toBe('/api/artwork/w342/p.jpg?token=t');
    expect(artworkUrl(img, 'large')).toBe('/api/artwork/w780/p.jpg?token=t');
    expect(artworkUrl(null, 'small')).toBeNull();
  });
});
