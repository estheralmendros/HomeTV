// Types of the fix-match dialog, importable without loading the
// lazy admin chunk (type-only).
import type { LibraryType, UnmatchedParsed, UnmatchedReason } from '@/api/types';

export type FixMatchMode =
  | { kind: 'film' | 'show'; id: number; title: string; year: number | null }
  | {
      kind: 'file';
      entryId: number;
      library: LibraryType;
      reason: UnmatchedReason | (string & {});
      parsed: UnmatchedParsed | null;
      relative_path: string;
    };

/** The film/show the match or resolve ended on (a merge may return another id). */
export interface MatchedTarget {
  type: 'film' | 'show';
  id: number;
}

export interface FixMatchDialogProps {
  mode: FixMatchMode;
  onClose: () => void;
  /** Called after a successful apply (before onClose); detail pages redirect to a merge survivor. */
  onMatched?: (target: MatchedTarget) => void;
}

/** The name in the dialog title: the item title, else the file name of the entry. */
export function fixMatchName(mode: FixMatchMode): string {
  if (mode.kind !== 'file') return mode.title;
  return mode.relative_path.split('/').pop() ?? mode.relative_path;
}
