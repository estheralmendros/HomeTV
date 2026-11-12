// useSubtitleStyleSave: the module-wide latest-wins queue is tied to the profile that made each wish
// (review-1: a queued look is never sent with another profile's token).
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { SubtitleStyle } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import * as f from '../../tests/fixtures';
import { until } from '../../tests/flush';
import { API } from '../../tests/msw/handlers';
import { recordRequests, server } from '../../tests/msw/server';
import { renderWithProviders } from '../../tests/renderWithProviders';
import { setProfileGetter } from './trackChoiceSession';
import { subtitleStyleSaveIdle, useSubtitleStyleSave } from './useSubtitleStyleSave';

const GREEN: SubtitleStyle = f.subtitleStyle({ color: 'green' });
const CYAN: SubtitleStyle = f.subtitleStyle({ color: 'cyan' });

function Harness() {
  const { signIn } = useAuth();
  const save = useSubtitleStyleSave(() => undefined);
  return (
    <>
      <button type="button" onClick={() => save(GREEN)}>
        green
      </button>
      <button type="button" onClick={() => save(CYAN)}>
        cyan
      </button>
      <button type="button" onClick={() => signIn(f.sessionResponse(f.profile({ id: 2, name: 'Kids' })))}>
        kids
      </button>
    </>
  );
}

/** The first PUT /api/me/subtitle-style is held until `release()`; later ones answer at once. */
function gateFirstStylePut() {
  const gates: Array<() => void> = [];
  let n = 0;
  server.use(
    http.put(`${API}/me/subtitle-style`, async () => {
      n += 1;
      if (n === 1) await new Promise<void>((resolve) => gates.push(resolve));
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return { release: () => gates.shift()?.(), pending: () => gates.length };
}

describe('useSubtitleStyleSave: profile safety', () => {
  it('a look queued under profile 1 is never sent after switching to profile 2', async () => {
    const rec = recordRequests();
    const gate = gateFirstStylePut();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'green' }));
    await user.click(screen.getByRole('button', { name: 'cyan' }));
    await until(() => expect(gate.pending()).toBe(1));
    await user.click(screen.getByRole('button', { name: 'kids' }));
    gate.release();
    // Idle = the first PUT settled and a queued wish (none here) was sent and settled too.
    await subtitleStyleSaveIdle();
    await rec.settled();
    const sent = rec.find('PUT', '/api/me/subtitle-style');
    expect(sent).toHaveLength(1);
    expect(sent[0]?.headers.get('authorization')).toBe('Bearer token-1');
  });

  it('a queued look is dropped at send time when the session profile changed without a sign-in (another tab)', async () => {
    const rec = recordRequests();
    const gate = gateFirstStylePut();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'green' }));
    await user.click(screen.getByRole('button', { name: 'cyan' }));
    await until(() => expect(gate.pending()).toBe(1));
    setProfileGetter(() => 2);
    gate.release();
    await subtitleStyleSaveIdle();
    await rec.settled();
    expect(rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(1);
  });

  it('a look queued behind an in-flight one under the same profile is sent once it settles (latest wins)', async () => {
    const rec = recordRequests();
    const gate = gateFirstStylePut();
    const { user } = renderWithProviders(<Harness />, { path: '/' });
    await user.click(screen.getByRole('button', { name: 'green' }));
    await user.click(screen.getByRole('button', { name: 'cyan' }));
    await until(() => expect(gate.pending()).toBe(1));
    gate.release();
    await until(() => expect(rec.find('PUT', '/api/me/subtitle-style')).toHaveLength(2));
    await subtitleStyleSaveIdle();
    await rec.settled();
    expect(rec.find('PUT', '/api/me/subtitle-style').map((c) => c.body)).toEqual([GREEN, CYAN]);
  });
});
