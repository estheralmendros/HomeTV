import { Link } from 'react-router-dom';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { S } from '@/strings.es';
import { EmptyState } from './EmptyState';

export function NotFound() {
  useDocumentTitle(S.titles.notFound);
  return (
    <main className="page">
      <EmptyState message={S.notFound.title}>
        <Link to="/" className="button button-primary">
          {S.notFound.goHome}
        </Link>
      </EmptyState>
    </main>
  );
}
