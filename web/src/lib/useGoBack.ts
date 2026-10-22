import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/** navigate(-1) when this tab has in-app history, else replace with `fallback`. */
export function useGoBack(fallback = '/') {
  const navigate = useNavigate();
  const location = useLocation();
  return useCallback(() => {
    if (location.key === 'default') navigate(fallback, { replace: true });
    else navigate(-1);
  }, [fallback, location.key, navigate]);
}
