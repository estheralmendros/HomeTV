import type { PersonAppearance } from '@/api/types';
import { S } from '@/strings.es';

/** Card subtitle of an appearance: "como Paul Atreides" (films and shows), null without a character. */
export function appearanceSubtitle(a: PersonAppearance): string | null {
  return a.character ? S.person.asCharacter(a.character) : null;
}
