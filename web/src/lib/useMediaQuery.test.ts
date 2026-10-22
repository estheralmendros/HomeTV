import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { setViewportWidth } from '../../tests/viewport';
import { PHONE_QUERY, useMediaQuery } from './useMediaQuery';

describe('useMediaQuery', () => {
  it('reports the current match and follows viewport changes', () => {
    const { result } = renderHook(() => useMediaQuery(PHONE_QUERY));
    expect(result.current).toBe(false);
    act(() => setViewportWidth(599));
    expect(result.current).toBe(true);
    act(() => setViewportWidth(600));
    expect(result.current).toBe(false);
  });

  it('is false when matchMedia is unavailable', () => {
    Object.assign(window, { matchMedia: undefined });
    const { result } = renderHook(() => useMediaQuery(PHONE_QUERY));
    expect(result.current).toBe(false);
  });
});
