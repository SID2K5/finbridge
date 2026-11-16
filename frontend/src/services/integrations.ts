import type {
  ConfigView,
  HealthCheck,
  IntegrationLogDetail,
  IntegrationLogSummary,
  IntegrationStatus,
  SettingView,
  SyncRun,
  SyncRunDetail,
} from '../types/api';
import { getData, getPage, send } from './api';

export interface LogFilter {
  type?: string;
  outcome?: string;
  reference?: string;
  correlationId?: string;
  dateFrom?: string;
  dateTo?: string;
}

export const integrationService = {
  status: () => getData<IntegrationStatus>('/integrations/status'),
  healthCheck: () => send<HealthCheck>('post', '/integrations/health-check'),

  startSync: (fullResync: boolean) => send<SyncRun>('post', '/integrations/odoo/sync', { fullResync }),
  syncRuns: (page: number, size: number) => getPage<SyncRun>('/integrations/odoo/sync-runs', { page, size }),
  syncRun: (id: number) => getData<SyncRunDetail>(`/integrations/odoo/sync-runs/${id}`, { size: 100 }),

  logs: (filter: LogFilter, page: number, size: number, sort?: string) =>
    getPage<IntegrationLogSummary>('/integrations/logs', {
      type: filter.type,
      outcome: filter.outcome,
      reference: filter.reference,
      correlationId: filter.correlationId,
      from: filter.dateFrom ? `${filter.dateFrom}T00:00:00Z` : undefined,
      to: filter.dateTo ? `${filter.dateTo}T23:59:59Z` : undefined,
      page,
      size,
      sort,
    }),
  log: (id: number) => getData<IntegrationLogDetail>(`/integrations/logs/${id}`),
  /** Starts again the operation behind a retryable failed call; the stored request is never replayed. */
  retryLog: (id: number) =>
    send<{ action: string; detail: string; syncRunId?: number; paymentId?: number; paymentStatus?: string }>(
      'post',
      `/integrations/logs/${id}/retry`,
    ),

  config: () => getData<ConfigView>('/integrations/config'),
  updateSetting: (key: string, value: string) =>
    send<SettingView>('put', `/integrations/config/${encodeURIComponent(key)}`, { value }),
};

/** Polls a sync run until it leaves RUNNING (or two minutes pass) and returns its final state. */
export async function waitForSyncRun(id: number): Promise<SyncRun> {
  for (let attempt = 0; attempt < 240; attempt++) {
    const detail = await integrationService.syncRun(id);
    if (detail.run.status !== 'RUNNING') {
      return detail.run;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('The synchronization is still running. Check Integration Monitoring in a moment.');
}
