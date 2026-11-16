import { useCallback, useRef, useState } from 'react';
import { toApiError, type ApiError } from '../services/api';

export interface AsyncAction<Args extends unknown[], Result> {
  /** Resolves with the result, or undefined when the action failed (the error is in "error"). */
  run: (...args: Args) => Promise<Result | undefined>;
  running: boolean;
  error: ApiError | null;
  clearError: () => void;
}

/**
 * Wraps a mutating call (pay, retry, review, save). While it runs, a second click does
 * nothing: the action cannot be started twice.
 */
export function useAsyncAction<Args extends unknown[], Result>(
  action: (...args: Args) => Promise<Result>,
): AsyncAction<Args, Result> {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const busy = useRef(false);
  const actionRef = useRef(action);
  actionRef.current = action;

  const run = useCallback(async (...args: Args) => {
    if (busy.current) {
      return undefined;
    }
    busy.current = true;
    setRunning(true);
    setError(null);
    try {
      return await actionRef.current(...args);
    } catch (failure) {
      setError(toApiError(failure));
      return undefined;
    } finally {
      busy.current = false;
      setRunning(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);
  return { run, running, error, clearError };
}
