import { useState } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, type Column } from '../components/DataTable';
import { Loading, PageState } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, InlineError, Modal, PageHeader } from '../components/ui';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { useQuery } from '../hooks/useQuery';
import { dateTime } from '../lib/format';
import { integrationService } from '../services/integrations';
import type { CheckResult, LastError, SyncRun } from '../types/api';

const RUN_COLUMNS: Column<SyncRun>[] = [
  { key: 'id', header: 'Run', render: (row) => <span className="font-medium text-navy-800">{row.id}</span> },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
  { key: 'started', header: 'Started', className: 'whitespace-nowrap', render: (row) => dateTime(row.startedAt) },
  { key: 'suppliers', header: 'Suppliers', align: 'right', render: (row) => row.suppliersUpserted },
  { key: 'created', header: 'New', align: 'right', render: (row) => row.invoicesCreated },
  { key: 'updated', header: 'Updated', align: 'right', render: (row) => row.invoicesUpdated },
  { key: 'unchanged', header: 'Unchanged', align: 'right', render: (row) => row.invoicesUnchanged },
  { key: 'rejected', header: 'Rejected', align: 'right', render: (row) => (row.invoicesRejected > 0 ? <span className="font-semibold text-red-700">{row.invoicesRejected}</span> : 0) },
  { key: 'message', header: 'Note', className: 'max-w-sm text-xs text-slate-600', render: (row) => row.errorMessage ?? '–' },
];

export function IntegrationsPage() {
  const toast = useToast();
  const status = useQuery(() => integrationService.status(), []);
  const runs = usePagedQuery((page, size) => integrationService.syncRuns(page, size), 'runs', 10);
  const [confirmCheck, setConfirmCheck] = useState(false);
  const [openRun, setOpenRun] = useState<number | null>(null);
  const check = useAsyncAction(() => integrationService.healthCheck());
  const [lastCheck, setLastCheck] = useState<CheckResult[] | null>(null);

  const runCheck = async () => {
    const result = await check.run();
    if (!result) {
      return;
    }
    setConfirmCheck(false);
    const results = [result.odoo, result.soap, result.rest];
    setLastCheck(results);
    const failed = results.filter((item) => !item.ok);
    if (failed.length === 0) {
      toast.success('Health check passed: Odoo, the SOAP payment service and the simulator REST endpoint all answered.');
    } else {
      toast.error(`Health check: ${failed.map((item) => `${item.target} ${item.errorCode ?? 'failed'}`).join(', ')}.`);
    }
    status.reload();
  };

  const data = status.data;

  return (
    <>
      <PageHeader
        title="Integration monitoring"
        subtitle="The state of each external connection, taken from the calls FinBridge actually made."
        actions={
          <button type="button" className="btn-primary" onClick={() => { check.clearError(); setConfirmCheck(true); }}>
            Run health check
          </button>
        }
      />

      <PageState loading={status.loading} error={status.error} onRetry={status.reload}>
        {data && (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <ConnectionCard
              name="Odoo"
              protocol="JSON-2 API"
              state={data.odoo.state}
              url={`${data.odoo.baseUrl} · database ${data.odoo.database}`}
              lastCallAt={data.odoo.lastCallAt}
              lastError={data.odoo.lastError}
              extra={
                <>
                  <p>
                    Mode: <strong className={data.odoo.mode === 'MOCK' ? 'text-amber-700' : ''}>{data.odoo.mode}</strong>
                  </p>
                  <p>
                    Last sync:{' '}
                    {data.odoo.lastSync ? (
                      <>
                        run {data.odoo.lastSync.id}, <StatusBadge status={data.odoo.lastSync.status} />{' '}
                        {dateTime(data.odoo.lastSync.finishedAt ?? data.odoo.lastSync.startedAt)}
                      </>
                    ) : (
                      'never'
                    )}
                  </p>
                </>
              }
              check={lastCheck?.find((item) => item.target === 'odoo')}
            />
            <ConnectionCard
              name="Payment service"
              protocol="SOAP 1.1"
              state={data.soap.state}
              url={data.soap.url}
              lastCallAt={data.soap.lastCallAt}
              lastError={data.soap.lastError}
              check={lastCheck?.find((item) => item.target === 'soap')}
            />
            <ConnectionCard
              name="Simulator controls"
              protocol="REST"
              state={data.rest.state}
              url={data.rest.url}
              lastCallAt={data.rest.lastCallAt}
              lastError={data.rest.lastError}
              check={lastCheck?.find((item) => item.target === 'rest')}
            />
          </div>
        )}
      </PageState>

      <Card
        title="Odoo synchronization runs"
        className="mt-5"
        actions={
          <Link to="/integrations/logs" className="text-sm font-medium text-navy-700 underline">
            Integration logs
          </Link>
        }
      >
        <DataTable
          caption="Synchronization runs"
          columns={RUN_COLUMNS}
          rows={runs.items}
          rowKey={(row) => row.id}
          loading={runs.loading}
          error={runs.error}
          onRetry={runs.reload}
          emptyTitle="No synchronization has run yet"
          emptyHint="Start one from the Invoices page."
          meta={runs.meta}
          onPage={runs.setPage}
          onRowClick={(row) => setOpenRun(row.id)}
        />
      </Card>

      {confirmCheck && (
        <ConfirmDialog
          title="Run a health check"
          confirmLabel="Run health check"
          onConfirm={() => void runCheck()}
          onCancel={() => setConfirmCheck(false)}
          running={check.running}
          error={check.error}
        >
          <p>
            FinBridge calls Odoo, fetches the payment service's WSDL and asks the simulator for its health, now. Each
            call is written to the integration log. Nothing is changed in any system.
          </p>
        </ConfirmDialog>
      )}

      {openRun !== null && <RunHistory runId={openRun} onClose={() => setOpenRun(null)} />}
    </>
  );
}

interface ConnectionProps {
  name: string;
  protocol: string;
  state: string;
  url: string;
  lastCallAt?: string;
  lastError?: LastError;
  extra?: React.ReactNode;
  check?: CheckResult;
}

function ConnectionCard({ name, protocol, state, url, lastCallAt, lastError, extra, check }: ConnectionProps) {
  return (
    <section className="card px-4 py-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-navy-900">{name}</h2>
          <p className="text-xs text-slate-500">{protocol}</p>
        </div>
        <StatusBadge status={state} />
      </div>
      <div className="mt-3 space-y-1.5 text-sm text-slate-700">
        <p className="break-all font-mono text-xs text-slate-500">{url}</p>
        {extra}
        <p>Last call: {lastCallAt ? dateTime(lastCallAt) : 'none yet'}</p>
        {lastError ? (
          <div className="rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-600">
            <p className="font-semibold text-slate-700">
              Last failure, {dateTime(lastError.at)}: {lastError.code ?? 'error'}
            </p>
            <p className="mt-0.5 break-words">
              {lastError.operation}
              {lastError.message ? `: ${lastError.message}` : ''}
            </p>
          </div>
        ) : (
          <p className="text-xs text-slate-500">No failure recorded.</p>
        )}
        {check && (
          <p className={`rounded-md px-3 py-2 text-xs font-medium ${check.ok ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-800'}`} role="status">
            Health check just now: {check.ok ? `answered in ${check.durationMs} ms` : `${check.errorCode ?? 'failed'}. ${check.message ?? ''}`}
            {check.ok && check.message ? ` (${check.message})` : ''}
          </p>
        )}
      </div>
    </section>
  );
}

/** What one synchronization run did with each Odoo record. */
function RunHistory({ runId, onClose }: { runId: number; onClose: () => void }) {
  const detail = useQuery(() => integrationService.syncRun(runId), [runId]);
  return (
    <Modal
      title={`Synchronization run ${runId}`}
      onClose={onClose}
      wide
      footer={
        <button type="button" className="btn-secondary" onClick={onClose}>
          Close
        </button>
      }
    >
      {detail.loading && <Loading />}
      <InlineError error={detail.error} />
      {detail.data && (
        <>
          <p>
            <StatusBadge status={detail.data.run.status} />{' '}
            <span className="ml-2 text-slate-600">
              {dateTime(detail.data.run.startedAt)} to {dateTime(detail.data.run.finishedAt)}
            </span>
          </p>
          {detail.data.run.errorMessage && <p className="text-red-800">{detail.data.run.errorMessage}</p>}
          {detail.data.history.length === 0 ? (
            <p className="italic text-slate-500">Nothing in Odoo had changed, so this run touched no record.</p>
          ) : (
            <ul className="max-h-80 divide-y divide-slate-100 overflow-y-auto rounded-md border border-slate-200">
              {detail.data.history.map((entry) => (
                <li key={entry.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
                  <span className="w-20 text-xs uppercase text-slate-500">{entry.entityType}</span>
                  <span className="w-24 font-mono text-xs">Odoo {entry.odooRecordId}</span>
                  <StatusBadge status={entry.action} tone={entry.action === 'CREATED' || entry.action === 'UPDATED' ? 'green' : undefined} />
                  {entry.message && <span className="text-xs text-slate-600">{entry.message}</span>}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Modal>
  );
}
