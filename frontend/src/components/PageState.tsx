import type { ReactNode } from 'react';
import type { ApiError } from '../services/api';

/** The three states every data view has besides "here is the data": loading, failed, nothing there. */

export function Loading({ what = 'Loading' }: { what?: string }) {
  return (
    <div className="flex items-center justify-center gap-3 px-6 py-12 text-sm text-slate-500" role="status">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-navy-700" aria-hidden="true" />
      {what}…
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div className="px-6 py-10 text-center" role="alert">
      <p className="text-sm font-semibold text-red-800">This could not be loaded</p>
      <p className="mx-auto mt-1 max-w-xl text-sm text-slate-600">{error.message}</p>
      {error.correlationId && (
        <p className="mt-2 font-mono text-xs text-slate-400">Correlation id {error.correlationId}</p>
      )}
      {onRetry && (
        <button type="button" className="btn-secondary mt-4" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="px-6 py-12 text-center">
      <p className="text-sm font-semibold text-slate-700">{title}</p>
      {hint && <p className="mx-auto mt-1 max-w-xl text-sm text-slate-500">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

interface PageStateProps {
  loading: boolean;
  error: ApiError | null;
  onRetry?: () => void;
  /** True when loading finished without error and there is nothing to show. */
  empty?: boolean;
  emptyTitle?: string;
  emptyHint?: string;
  children: ReactNode;
}

/** Shows exactly one of: loading, error, empty, or the content. */
export function PageState({ loading, error, onRetry, empty, emptyTitle, emptyHint, children }: PageStateProps) {
  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />;
  }
  if (loading) {
    return <Loading />;
  }
  if (empty) {
    return <EmptyState title={emptyTitle ?? 'Nothing here yet'} hint={emptyHint} />;
  }
  return <>{children}</>;
}
