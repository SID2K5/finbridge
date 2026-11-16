import { useState } from 'react';
import { DataTable, type Column } from '../components/DataTable';
import { Loading } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, DefinitionList, Field, FilterBar, InlineError, PageHeader } from '../components/ui';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { useQuery } from '../hooks/useQuery';
import { dateTime } from '../lib/format';
import { integrationService, type LogFilter } from '../services/integrations';
import type { IntegrationLogSummary } from '../types/api';

const EMPTY: LogFilter = {};

const COLUMNS: Column<IntegrationLogSummary>[] = [
  { key: 'at', header: 'When', sortField: 'createdAt', className: 'whitespace-nowrap', render: (row) => dateTime(row.createdAt) },
  { key: 'type', header: 'System', render: (row) => row.type },
  { key: 'operation', header: 'Operation', sortField: 'operation', render: (row) => <span className="font-mono text-xs">{row.operation}</span> },
  { key: 'outcome', header: 'Outcome', sortField: 'outcome', render: (row) => <StatusBadge status={row.outcome} /> },
  { key: 'http', header: 'HTTP', align: 'right', render: (row) => row.httpStatus ?? '–' },
  { key: 'duration', header: 'Duration', sortField: 'durationMs', align: 'right', className: 'whitespace-nowrap', render: (row) => `${row.durationMs} ms` },
  { key: 'error', header: 'Error code', render: (row) => (row.errorCode ? <span className="text-xs text-red-700">{row.errorCode}</span> : '–') },
  { key: 'reference', header: 'Reference', render: (row) => <span className="font-mono text-xs">{row.requestReference ?? '–'}</span> },
];

export function IntegrationLogsPage() {
  const [filter, setFilter] = useState<LogFilter>(EMPTY);
  const [openId, setOpenId] = useState<number | null>(null);
  const table = usePagedQuery(
    (page, size, sort) => integrationService.logs(filter, page, size, sort),
    JSON.stringify(filter),
  );
  const set = (patch: Partial<LogFilter>) => setFilter((current) => ({ ...current, ...patch }));
  const active = Object.values(filter).some((value) => value !== undefined && value !== '');

  return (
    <>
      <PageHeader title="Integration logs" subtitle="One row per call to Odoo, the SOAP payment service or the simulator. Select a row for its payloads." />
      <Card>
        <FilterBar onReset={() => setFilter(EMPTY)} active={active}>
          <Field label="System" className="w-36">
            <select className="input" value={filter.type ?? ''} onChange={(event) => set({ type: event.target.value })}>
              <option value="">All</option>
              <option value="ODOO">Odoo</option>
              <option value="SOAP">SOAP</option>
              <option value="REST">REST</option>
            </select>
          </Field>
          <Field label="Outcome" className="w-36">
            <select className="input" value={filter.outcome ?? ''} onChange={(event) => set({ outcome: event.target.value })}>
              <option value="">Any</option>
              <option value="SUCCESS">Success</option>
              <option value="FAILURE">Failure</option>
              <option value="TIMEOUT">Timeout</option>
            </select>
          </Field>
          <Field label="From" className="w-40">
            <input className="input" type="date" value={filter.dateFrom ?? ''} onChange={(event) => set({ dateFrom: event.target.value })} />
          </Field>
          <Field label="To" className="w-40">
            <input className="input" type="date" value={filter.dateTo ?? ''} onChange={(event) => set({ dateTo: event.target.value })} />
          </Field>
          <Field label="Reference" className="w-52">
            <input className="input" type="search" placeholder="FB-… or sync-run-1" value={filter.reference ?? ''} onChange={(event) => set({ reference: event.target.value })} />
          </Field>
          <Field label="Correlation id" className="w-72">
            <input className="input font-mono text-xs" type="search" placeholder="Exact id" value={filter.correlationId ?? ''} onChange={(event) => set({ correlationId: event.target.value })} />
          </Field>
        </FilterBar>
        <DataTable
          caption="Integration logs"
          columns={COLUMNS}
          rows={table.items}
          rowKey={(row) => row.id}
          loading={table.loading}
          error={table.error}
          onRetry={table.reload}
          emptyTitle={active ? 'No log entry matches these filters' : 'No call to an external system has been logged yet'}
          meta={table.meta}
          onPage={table.setPage}
          sort={table.sort}
          onSort={table.toggleSort}
          onRowClick={(row) => setOpenId(row.id)}
        />
      </Card>
      {openId !== null && <LogDrawer id={openId} onRetried={table.reload} onClose={() => setOpenId(null)} onCorrelation={(correlationId) => { setFilter({ correlationId }); setOpenId(null); }} />}
    </>
  );
}

/** A panel from the right with everything stored about one call. */
function LogDrawer({ id, onClose, onCorrelation, onRetried }: { id: number; onClose: () => void; onCorrelation: (correlationId: string) => void; onRetried: () => void }) {
  const toast = useToast();
  const log = useQuery(() => integrationService.log(id), [id]);
  const data = log.data;
  const [confirming, setConfirming] = useState(false);
  const retry = useAsyncAction(() => integrationService.retryLog(id));
  const canRetry = !!data && data.retryable && data.outcome !== 'SUCCESS';

  const confirmRetry = async () => {
    const result = await retry.run();
    if (!result) {
      return;
    }
    setConfirming(false);
    toast.success(`${result.detail}.`);
    onRetried();
  };
  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-navy-950/40" onMouseDown={(event) => { if (event.target === event.currentTarget) { onClose(); } }}>
      <aside role="dialog" aria-modal="true" aria-label={`Integration log entry ${id}`} className="flex h-full w-full max-w-2xl flex-col bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3.5">
          <h2 className="text-base font-semibold text-navy-900">Log entry {id}</h2>
          <button type="button" className="btn-secondary" onClick={onClose} autoFocus>
            Close
          </button>
        </div>
        <div className="flex-1 space-y-4 overflow-y-auto px-1 py-2">
          {log.loading && <Loading />}
          <div className="px-4">
            <InlineError error={log.error} />
          </div>
          {data && (
            <>
              <DefinitionList
                items={[
                  { term: 'When', value: dateTime(data.createdAt) },
                  { term: 'System', value: data.type },
                  { term: 'Operation', value: <span className="font-mono text-xs">{data.operation}</span> },
                  { term: 'Outcome', value: <StatusBadge status={data.outcome} /> },
                  { term: 'HTTP status', value: data.httpStatus },
                  { term: 'Duration', value: `${data.durationMs} ms` },
                  { term: 'Reference', value: data.requestReference },
                  { term: 'Error code', value: data.errorCode },
                  { term: 'Retryable', value: data.retryable ? 'Yes' : 'No' },
                ]}
              />
              <div className="space-y-4 px-4 pb-4">
                {data.errorDescription && (
                  <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">{data.errorDescription}</p>
                )}
                <p className="text-sm">
                  <span className="label">Correlation id</span>
                  <span className="font-mono text-xs">{data.correlationId}</span>{' '}
                  <button type="button" className="ml-2 text-sm font-medium text-navy-700 underline" onClick={() => onCorrelation(data.correlationId)}>
                    Show every call of this request
                  </button>
                </p>
                {canRetry && (
                  <div className="flex flex-wrap items-center gap-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm">
                    <span className="flex-1 text-slate-700">This failure is marked retryable.</span>
                    <button type="button" className="btn-primary" onClick={() => { retry.clearError(); setConfirming(true); }}>
                      Retry the operation
                    </button>
                  </div>
                )}
                <Payload title="Request payload" text={data.requestPayload} />
                <Payload title="Response payload" text={data.responsePayload} />
                <p className="text-xs text-slate-500">Payloads are stored with credentials masked and are cut off after 16,000 characters.</p>
              </div>
            </>
          )}
        </div>
      </aside>
      {confirming && data && (
        <ConfirmDialog title="Retry this operation" confirmLabel="Retry" onConfirm={() => void confirmRetry()} onCancel={() => setConfirming(false)} running={retry.running} error={retry.error}>
          <p>
            {data.type === 'ODOO' && 'A new synchronization run is started. It only reads from Odoo.'}
            {data.type === 'SOAP' && 'The payment this call belongs to is retried or its status is checked, under the payment rules. It cannot be paid twice.'}
            {data.type === 'REST' && 'A new health check is run.'}
          </p>
          <p className="text-slate-500">The stored request is not sent again as it is.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}

function Payload({ title, text }: { title: string; text?: string }) {
  return (
    <div>
      <p className="label">{title}</p>
      {text ? (
        <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-all rounded-md bg-slate-900 p-3 font-mono text-xs leading-relaxed text-slate-100">
          {text.replace(/></g, '>\n<')}
        </pre>
      ) : (
        <p className="text-sm italic text-slate-400">None stored.</p>
      )}
    </div>
  );
}
