import { Link } from 'react-router-dom';
import { EmptyState } from './EmptyState';

/** Detail-page 404 (item gone or on a disconnected drive) with a link back to its grid. */
export function NotAvailable({ message, backTo, backLabel }: { message: string; backTo: string; backLabel: string }) {
  return (
    <div className="page">
      <EmptyState message={message}>
        <Link to={backTo} className="button">
          {backLabel}
        </Link>
      </EmptyState>
    </div>
  );
}
