import { useCallback, useEffect, useRef, useState } from 'react';
import { toApiError, type ApiError } from '../services/api';

export interface QueryState<T> {
  data: T | undefined;
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
}

/**
 * Loads one thing from the backend and tracks its three states. The fetcher runs again
 * whenever a value in deps changes or reload() is called; an answer that arrives after a
 * newer request was started is ignored, so a slow old response can never overwrite a new one.
 */
export function useQuery<T>(fetcher: () => Promise<T>, deps: readonly unknown[]): QueryState<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [version, setVersion] = useState(0);
  const latest = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    const request = ++latest.current;
    setLoading(true);
    fetcherRef
      .current()
      .then((result) => {
        if (request === latest.current) {
          setData(result);
          setError(null);
        }
      })
      .catch((failure: unknown) => {
        if (request === latest.current) {
          setError(toApiError(failure));
        }
      })
      .finally(() => {
        if (request === latest.current) {
          setLoading(false);
        }
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((current) => current + 1), []);
  return { data, loading, error, reload };
}
