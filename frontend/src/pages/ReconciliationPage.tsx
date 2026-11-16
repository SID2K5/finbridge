import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { DataTable, type Column } from '../components/DataTable';
import { EmptyState, PageState } from '../components/PageState';
import { ReviewBadge, StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, Field, FilterBar, PageHeader } from '../components/ui';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { useQuery } from '../hooks/useQuery';
import { date, dateTime, label, money, todayUtc } from '../lib/format';
import { reconciliationService } from '../services/reconciliation';
import type { ReconciliationRecord, ReconciliationRun } from '../types/api';

const STATUSES = ['MATCHED', 'MISMATCH', 'MISSING_EXTERNAL_RECORD', 'MISSING_INTERNAL_RECORD', 'DUPLICATE', 'PENDING_REVIEW'];
const REVIEW_STATUSES = ['OPEN', 'NEEDS_INVESTIGATION', 'RESOLVED', 'NOT_REQUIRED'];

const COLUMNS: Column<ReconciliationRecord>[] = [
  { key: 'status', header: 'Result', sortField: 'status', render: (row) => <StatusBadge status={row.status} /> },
  { key: 'review', header: 'Review', sortField: 'reviewStatus', render: (row) => <ReviewBadge status={row.reviewStatus} /> },
  { key: 'internal', header: 'FinBridge reference', className: 'whitespace-nowrap', render: (row) => <span className="font-mono text-xs">{row.internalTransactionReference ?? '–'}</span> },
  { key: 'external', header: 'Payment service id', className: 'whitespace-nowrap', render: (row) => <span className="font-mono text-xs">{row.externalTransactionReference ?? '–'}</span> },
  { key: 'expected', header: 'Expected', sortField: 'expectedAmount', align: 'right', className: 'whitespace-nowrap', render: (row) => money(row.expectedAmount, row.currency) },
  { key: 'actual', header: 'Actual', align: 'right', className: 'whitespace-nowrap', render: (row) => money(row.actualAmount, row.currency) },
  { key: 'description', header: 'What was found', className: 'min-w-[26rem] text-xs text-slate-600', render: (row) => row.discrepancyDescription ?? '–' },
];

async function waitForRun(id: number): Promise<ReconciliationRun> {
  for (let attempt = 0; attempt < 240; attempt++) {
    const run = await reconciliationService.run(id);
    if (run.status !== 'RUNNING') {
      return run;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('The reconciliation is still running. Reload this page in a moment.');
}

export function ReconciliationPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const runs = useQuery(() => reconciliationService.runs(), []);
  const selectedId = params.get('run') ? Number(params.get('run')) : runs.data?.items[0]?.id;
  const [status, setStatus] = useState('');
  const [reviewStatus, setReviewStatus] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [periodFrom, setPeriodFrom] = useState(todayUtc());
  const [periodTo, setPeriodTo] = useState(todayUtc());

  const run = useQuery(
    () => (selectedId ? reconciliationService.run(selectedId) : Promise.resolve(undefined)),
    [selectedId],
  );
  const table = usePagedQuery(
    (page, size, sort) =>
      selectedId
        ? reconciliationService.records({ runId: selectedId, status, reviewStatus }, page, size, sort)
        : Promise.resolve({ items: [], meta: { page: 0, size, totalElements: 0, totalPages: 0 } }),
    `${selectedId}|${status}|${reviewStatus}`,
  );

  // Filters belong to one run; switching runs starts clean.
  useEffect(() => {
    setStatus('');
    setReviewStatus('');
  }, [selectedId]);

  const start = useAsyncAction(async () => {
    const started = await reconciliationService.start(periodFrom, periodTo);
    return waitForRun(started.id);
  });

  const confirmStart = async () => {
    const finished = await start.run();
    if (!finished) {
      return;
    }
    setDialogOpen(false);
    if (finished.status === 'COMPLETED') {
      toast.success(`Reconciliation completed: ${finished.totalRecords} records, ${finished.matchedCount} matched, ${finished.discrepancyCount} to review.`);
    } else {
      toast.error(`Reconciliation failed. ${finished.errorMessage ?? ''}`);
    }
    runs.reload();
    setParams({ run: String(finished.id) });
  };

  const selected = run.data;
  const noRuns = !runs.loading && !runs.error && (runs.data?.items.length ?? 0) === 0;

  return (
    <>
      <PageHeader
        title="Reconciliation"
        subtitle="FinBridge's payments compared with the payment service's settlement statement."
        actions={
          <button type="button" className="btn-primary" onClick={() => { start.clearError(); setDialogOpen(true); }}>
            Run reconciliation
          </button>
        }
      />

      <PageState loading={runs.loading} error={runs.error} onRetry={runs.reload}>
        {noRuns ? (
          <Card>
            <EmptyState
              title="No reconciliation has been run yet"
              hint="Choose a period and run one. It reads the settlement statement from the payment service and compares it with FinBridge's payments."
            />
          </Card>
        ) : (
          <div className="space-y-5">
            <Card>
              <div className="flex flex-wrap items-end gap-4 px-4 py-3">
                <Field label="Run" className="w-96 max-w-full">
                  <select className="input" value={selectedId ?? ''} onChange={(event) => setParams({ run: event.target.value })}>
                    {(runs.data?.items ?? []).map((item) => (
                      <option key={item.id} value={item.id}>
                        Run {item.id}: {date(item.periodFrom)} to {date(item.periodTo)} ({label(item.status).toLowerCase()})
                      </option>
                    ))}
                  </select>
                </Field>
                {selected && (
                  <p className="pb-2 text-sm text-slate-600">
                    <StatusBadge status={selected.status} /> <span className="ml-2">started {dateTime(selected.startedAt)}</span>
                  </p>
                )}
              </div>
              {selected?.status === 'FAILED' && (
                <p className="border-t border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
                  This run failed and has no records. {selected.errorMessage}
                </p>
              )}
              {selected?.countsByStatus && selected.status === 'COMPLETED' && (
                <div className="grid grid-cols-2 gap-px border-t border-slate-200 bg-slate-200 sm:grid-cols-3 lg:grid-cols-6">
                  {STATUSES.map((code) => (
                    <button
                      key={code}
                      type="button"
                      className={`bg-white px-4 py-3 text-left hover:bg-navy-50 ${status === code ? 'ring-2 ring-inset ring-navy-600' : ''}`}
                      onClick={() => setStatus(status === code ? '' : code)}
                      aria-pressed={status === code}
                    >
                      <span className="block text-2xl font-semibold tabular-nums text-navy-900">{selected.countsByStatus?.[code] ?? 0}</span>
                      <StatusBadge status={code} />
                    </button>
                  ))}
                </div>
              )}
            </Card>

            <Card title="Records">
              <FilterBar onReset={() => { setStatus(''); setReviewStatus(''); }} active={status !== '' || reviewStatus !== ''}>
                <Field label="Result" className="w-60">
                  <select className="input" value={status} onChange={(event) => setStatus(event.target.value)}>
                    <option value="">Any result</option>
                    {STATUSES.map((code) => (
                      <option key={code} value={code}>
                        {label(code)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Review" className="w-52">
                  <select className="input" value={reviewStatus} onChange={(event) => setReviewStatus(event.target.value)}>
                    <option value="">Any review state</option>
                    {REVIEW_STATUSES.map((code) => (
                      <option key={code} value={code}>
                        {label(code)}
                      </option>
                    ))}
                  </select>
                </Field>
              </FilterBar>
              <DataTable
                caption="Reconciliation records"
                columns={COLUMNS}
                rows={table.items}
                rowKey={(row) => row.id}
                loading={table.loading}
                error={table.error}
                onRetry={table.reload}
                emptyTitle={status || reviewStatus ? 'No record matches these filters' : 'This run has no records'}
                emptyHint={status || reviewStatus ? undefined : 'There was no payment and no statement entry in its period.'}
                meta={table.meta}
                onPage={table.setPage}
                sort={table.sort}
                onSort={table.toggleSort}
                onRowClick={(row) => navigate(`/reconciliation/${row.id}`)}
              />
            </Card>
          </div>
        )}
      </PageState>

      {dialogOpen && (
        <ConfirmDialog
          title="Run a reconciliation"
          confirmLabel="Run reconciliation"
          onConfirm={() => void confirmStart()}
          onCancel={() => setDialogOpen(false)}
          running={start.running}
          error={start.error}
          confirmDisabled={!periodFrom || !periodTo}
        >
          <p>
            Payments requested in this period are compared with the payment service's statement. Nothing is changed:
            the run only reports what it finds.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="From (UTC)">
              <input className="input" type="date" value={periodFrom} max={periodTo || undefined} onChange={(event) => setPeriodFrom(event.target.value)} disabled={start.running} />
            </Field>
            <Field label="To (UTC)">
              <input className="input" type="date" value={periodTo} min={periodFrom || undefined} onChange={(event) => setPeriodTo(event.target.value)} disabled={start.running} />
            </Field>
          </div>
        </ConfirmDialog>
      )}
    </>
  );
}
