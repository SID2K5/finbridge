import type { ReconciliationDetail, ReconciliationRecord, ReconciliationRun } from '../types/api';
import { getData, getPage, send } from './api';

export interface RecordFilter {
  runId?: number;
  status?: string;
  reviewStatus?: string;
}

export const reconciliationService = {
  runs: () => getPage<ReconciliationRun>('/reconciliation/runs', { size: 50 }),
  run: (id: number) => getData<ReconciliationRun>(`/reconciliation/runs/${id}`),
  start: (periodFrom: string, periodTo: string) =>
    send<ReconciliationRun>('post', '/reconciliation/run', { periodFrom, periodTo }),
  records: (filter: RecordFilter, page: number, size: number, sort?: string) =>
    getPage<ReconciliationRecord>('/reconciliation', { ...filter, page, size, sort }),
  record: (id: number) => getData<ReconciliationDetail>(`/reconciliation/${id}`),
  review: (id: number, reviewStatus: 'RESOLVED' | 'NEEDS_INVESTIGATION', note: string) =>
    send<ReconciliationDetail>('patch', `/reconciliation/${id}/review`, { reviewStatus, note }),
};
