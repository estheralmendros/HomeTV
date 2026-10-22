import { describe, expect, it } from 'vitest';
import { parseRouteId } from './routeId';

describe('parseRouteId', () => {
  it('accepts positive integers only', () => {
    expect(parseRouteId('12')).toBe(12);
    for (const raw of [undefined, '', '0', '-1', '1.5', 'abc', '12abc', '99999999999999999999']) expect(parseRouteId(raw)).toBeNull();
  });
});
