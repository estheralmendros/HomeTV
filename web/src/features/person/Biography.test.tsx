import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useOverflows } from '@/lib/useOverflows';
import { Biography } from './Biography';

// jsdom has no layout: the overflow measurement is mocked (useOverflows has its own test).
vi.mock('@/lib/useOverflows', () => ({ useOverflows: vi.fn(() => true) }));

const TEXT = 'Primera línea.\nSegunda línea.';

beforeEach(() => {
  vi.mocked(useOverflows).mockReturnValue(true);
});

describe('Biography', () => {
  it('starts clamped with "Leer más" controlling the text, and toggles to "Leer menos"', async () => {
    const user = userEvent.setup();
    render(<Biography text={TEXT} />);
    const text = screen.getByText(/Primera línea\./);
    expect(text.tagName).toBe('P');
    expect(text.textContent).toBe(TEXT);
    expect(text).toHaveClass('clamped');
    const toggle = screen.getByRole('button', { name: 'Leer más' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveAttribute('aria-controls', text.id);
    expect(text.id).not.toBe('');

    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Leer menos' })).toHaveAttribute('aria-expanded', 'true');
    expect(text).not.toHaveClass('clamped');
    expect(vi.mocked(useOverflows)).toHaveBeenLastCalledWith(expect.anything(), false, TEXT);

    await user.click(screen.getByRole('button', { name: 'Leer menos' }));
    expect(screen.getByRole('button', { name: 'Leer más' })).toHaveAttribute('aria-expanded', 'false');
    expect(text).toHaveClass('clamped');
  });

  it('a biography that fits has no toggle', () => {
    vi.mocked(useOverflows).mockReturnValue(false);
    render(<Biography text={TEXT} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(vi.mocked(useOverflows)).toHaveBeenLastCalledWith(expect.anything(), true, TEXT);
  });

  it('sets lang when given and omits it otherwise', () => {
    const { rerender } = render(<Biography text={TEXT} lang="en" />);
    expect(screen.getByText(/Primera línea\./)).toHaveAttribute('lang', 'en');
    rerender(<Biography text={TEXT} />);
    expect(screen.getByText(/Primera línea\./)).not.toHaveAttribute('lang');
  });
});
