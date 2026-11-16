import type { ReactNode } from 'react';
import type { SortState } from '../hooks/usePagedQuery';
import type { ApiError } from '../services/api';
import type { PageMeta } from '../types/api';
import { EmptyState, ErrorState, Loading } from './PageState';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  /** The backend's sort field. Leave out for a column that cannot be sorted. */
  sortField?: string;
  align?: 'left' | 'right';
  className?: string;
}

interface Props<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  loading: boolean;
  error: ApiError | null;
  onRetry?: () => void;
  emptyTitle: string;
  emptyHint?: string;
  meta?: PageMeta;
  onPage?: (page: number) => void;
  sort?: SortState | null;
  onSort?: (field: string) => void;
  onRowClick?: (row: T) => void;
  caption: string;
}

/** A table with server-side paging and sorting, and its own loading, empty and error states. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading,
  error,
  onRetry,
  emptyTitle,
  emptyHint,
  meta,
  onPage,
  sort,
  onSort,
  onRowClick,
  caption,
}: Props<T>) {
  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />;
  }
  // The first load shows a spinner; later loads keep the old rows visible and dim them.
  if (loading && rows.length === 0) {
    return <Loading />;
  }
  if (rows.length === 0) {
    return <EmptyState title={emptyTitle} hint={emptyHint} />;
  }

  return (
    <div>
      <div className={`overflow-x-auto ${loading ? 'opacity-60' : ''}`}>
        <table className="min-w-full divide-y divide-slate-200">
          <caption className="sr-only">{caption}</caption>
          <thead className="bg-slate-50">
            <tr>
              {columns.map((column) => {
                const active = sort && column.sortField && sort.field === column.sortField;
                return (
                  <th
                    key={column.key}
                    scope="col"
                    className={`th ${column.align === 'right' ? 'text-right' : ''}`}
                    aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined}
                  >
                    {column.sortField && onSort ? (
                      <button
                        type="button"
                        className="inline-flex items-center gap-1 uppercase tracking-wide hover:text-slate-800"
                        onClick={() => onSort(column.sortField as string)}
                      >
                        {column.header}
                        <span className="text-[10px] text-slate-400" aria-hidden="true">
                          {active ? (sort.direction === 'asc' ? '▲' : '▼') : '↕'}
                        </span>
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 bg-white">
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                className={onRowClick ? 'cursor-pointer hover:bg-navy-50' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={
                  onRowClick
                    ? (event) => {
                        if (event.key === 'Enter') {
                          onRowClick(row);
                        }
                      }
                    : undefined
                }
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={`td ${column.align === 'right' ? 'text-right tabular-nums' : ''} ${column.className ?? ''}`}
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {meta && onPage && (
        <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3 text-sm text-slate-600">
          <span>
            {meta.totalElements === 0
              ? 'No rows'
              : `Rows ${meta.page * meta.size + 1} to ${Math.min((meta.page + 1) * meta.size, meta.totalElements)} of ${meta.totalElements}`}
          </span>
          <div className="flex items-center gap-2">
            <button type="button" className="btn-secondary px-3 py-1.5" disabled={meta.page <= 0 || loading} onClick={() => onPage(meta.page - 1)}>
              Previous
            </button>
            <span className="tabular-nums">
              Page {meta.page + 1} of {Math.max(meta.totalPages, 1)}
            </span>
            <button
              type="button"
              className="btn-secondary px-3 py-1.5"
              disabled={meta.page + 1 >= meta.totalPages || loading}
              onClick={() => onPage(meta.page + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
