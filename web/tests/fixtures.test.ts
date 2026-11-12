import { describe, expect, it } from 'vitest';
import { DEFAULT_SUBTITLE_STYLE } from '@/api/subtitleStyleDefaults';
import * as f from './fixtures';

describe('f.me / f.trackChoice / f.subtitleStyle', () => {
  it('f.me = the profile + the default subtitle style', () => {
    expect(f.me()).toEqual({ ...f.profile(), subtitle_style: DEFAULT_SUBTITLE_STYLE });
    expect(f.me({ id: 2, name: 'Kids' })).toMatchObject({ id: 2, name: 'Kids', subtitle_style: DEFAULT_SUBTITLE_STYLE });
    expect(f.me({ subtitle_style: f.subtitleStyle({ color: 'yellow' }) }).subtitle_style.color).toBe('yellow');
  });

  it('f.subtitleStyle defaults to medium/white/outline/black/low', () => {
    expect(f.subtitleStyle()).toEqual({ size: 'medium', color: 'white', edge: 'outline', edge_color: 'black', position: 'low' });
  });

  it('f.trackChoice: an English 5.1 audio part, no subtitle part', () => {
    expect(f.trackChoice()).toEqual({ audio: { language: 'eng', title: null, channels: 6 }, subtitles: null });
    expect(f.trackChoice({ audio: null, subtitles: f.subtitleChoice() }).subtitles).toMatchObject({ mode: 'track', language: 'spa' });
  });
});

describe('f.meForToken (token-aware GET /api/me)', () => {
  it('answers the profile a session response was issued for', () => {
    const kids = f.profile({ id: 2, name: 'Kids' });
    f.sessionResponse(kids);
    expect(f.meForToken('Bearer token-2')).toEqual(f.me(kids));
  });

  it('records the final token (an override wins)', () => {
    const luis = f.profile({ id: 3, name: 'Luis' });
    f.sessionResponse(luis, { token: 'custom' });
    expect(f.meForToken('Bearer custom').name).toBe('Luis');
    expect(f.meForToken('Bearer token-3').name).toBe('Ana');
  });

  it('unknown or missing token → f.profile()', () => {
    expect(f.meForToken('Bearer nope')).toEqual(f.me());
    expect(f.meForToken(null)).toEqual(f.me());
  });

  it('resetSessionProfiles forgets the registered tokens', () => {
    f.sessionResponse(f.profile({ id: 2, name: 'Kids' }));
    f.resetSessionProfiles();
    expect(f.meForToken('Bearer token-2')).toEqual(f.me());
  });
});
