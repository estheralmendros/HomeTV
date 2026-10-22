import { useEffect } from 'react';
import { S } from '@/strings.es';

/** document.title = "{page} · HomeTV" (or "HomeTV" for null). */
export function useDocumentTitle(page: string | null | undefined): void {
  useEffect(() => {
    document.title = page ? S.titles.page(page) : S.titles.default;
  }, [page]);
}
