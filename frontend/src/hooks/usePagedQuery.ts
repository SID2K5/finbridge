import { useCallback, useEffect, useState } from 'react';
import type { ApiError } from '../services/api';
import type { Page, PageMeta } from '../types/api';
import { useQuery } from './useQuery';

export interface SortState {
  field: string;
  direction: 'asc' | 'desc';
}

export interface PagedQuery<T> {
  items: T[];
  meta: PageMeta | undefined;
  loading: boolean;
  error: ApiError | null;
  page: number;
  setPage: (page: number) => void;
  sort: SortState | null;
  toggleSort: (field: string) => void;
  reload: () => void;
}

/**
 * Server-side paging and sorting for a table. The backend does the work; this hook only
 * remembers which page and sort are shown. filterKey is any value that changes when the
 * filters change: the table then goes back to the first page.
 */
export function usePagedQuery<T>(
  fetcher: (page: number, size: number, sort: string | undefined) => Promise<Page<T>>,
  filterKey: string,
  size = 20,
): PagedQuery<T> {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortState | null>(null);

  useEffect(() => {
    setPage(0);
  }, [filterKey]);

  const sortParam = sort ? `${sort.field},${sort.direction}` : undefined;
  const query = useQuery(() => fetcher(page, size, sortParam), [page, size, sortParam, filterKey]);

  const toggleSort = useCallback((field: string) => {
    setPage(0);
    setSort((current) => {
      if (!current || current.field !== field) {
        return { field, direction: 'asc' };
      }
      return current.direction === 'asc' ? { field, direction: 'desc' } : null;
    });
  }, []);

  return {
    items: query.data?.items ?? [],
    meta: query.data?.meta,
    loading: query.loading,
    error: query.error,
    page,
    setPage,
    sort,
    toggleSort,
    reload: query.reload,
  };
}
