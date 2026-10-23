import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { historyIndex } from './historyIndex';

/**
 * navigate(-1) when this tab has in-app history before the current entry, else replace with
 * `fallback`. Uses React Router's history index (not `location.key`, which a replace changes
 * without adding an entry, e.g. dropping `?t=` or auto-advancing on a deep-linked /watch page).
 */
export function useGoBack(fallback = '/') {
  const navigate = useNavigate();
  return useCallback(() => {
    if (historyIndex.read() > 0) navigate(-1);
    else navigate(fallback, { replace: true });
  }, [fallback, navigate]);
}
