// The "Formato de los subtítulos" panel's draft: a tiny external store on PlayerShellContext,
// never React state of the shell. Leaf subscribers (the <style> element, the panel, the sample
// line) read the whole draft; usePlayerSubtitles reads only the effective position (a string), so
// the keyed PlayerPage re-renders on a "Posición" step only. null = no draft (the saved style).
import type { SubtitleStyle } from '@/api/types';

export interface SubtitleStyleDraft {
  get: () => SubtitleStyle | null;
  set: (style: SubtitleStyle | null) => void;
  subscribe: (listener: () => void) => () => void;
}

export function createSubtitleStyleDraft(): SubtitleStyleDraft {
  let draft: SubtitleStyle | null = null;
  const listeners = new Set<() => void>();
  return {
    get: () => draft,
    set: (style) => {
      if (style === draft) return;
      draft = style;
      for (const listener of [...listeners]) listener();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
