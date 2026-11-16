import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DataTable, type Column } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, Field, FilterBar, PageHeader } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { useQuery } from '../hooks/useQuery';
import { date, money } from '../lib/format';
import { OPERATIONS } from '../lib/roles';
import { integrationService, waitForSyncRun } from '../services/integrations';
import { invoiceService, supplierService, type InvoiceFilter } from '../services/invoices';
import type { InvoiceSummary } from '../types/api';

const INVOICE_STATUSES = ['OPEN', 'PAYMENT_IN_PROGRESS', 'PAID', 'PAYMENT_FAILED'];
const EMPTY: InvoiceFilter = {};

const COLUMNS: Column<InvoiceSummary>[] = [
  { key: 'number', header: 'Invoice', sortField: 'invoiceNumber', render: (row) => <span className="font-medium text-navy-800">{row.invoiceNumber}</span> },
  { key: 'supplier', header: 'Supplier', sortField: 'supplierName', render: (row) => row.supplierName },
  { key: 'date', header: 'Invoice date', sortField: 'invoiceDate', render: (row) => date(row.invoiceDate), className: 'whitespace-nowrap' },
  { key: 'due', header: 'Due', sortField: 'dueDate', render: (row) => date(row.dueDate), className: 'whitespace-nowrap' },
  { key: 'total', header: 'Total', sortField: 'totalAmount', align: 'right', render: (row) => money(row.totalAmount, row.currency), className: 'whitespace-nowrap' },
  { key: 'status', header: 'Payment status', sortField: 'status', render: (row) => <StatusBadge status={row.invoiceStatus} /> },
  { key: 'sync', header: 'Sync', render: (row) => <StatusBadge status={row.syncStatus} /> },
];

export function InvoicesPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const { hasRole } = useAuth();
  const [filter, setFilter] = useState<InvoiceFilter>(EMPTY);
  const [syncOpen, setSyncOpen] = useState(false);
  const [fullResync, setFullResync] = useState(false);

  const suppliers = useQuery(() => supplierService.all(), []);
  const table = usePagedQuery(
    (page, size, sort) => invoiceService.list(filter, page, size, sort),
    JSON.stringify(filter),
  );

  const sync = useAsyncAction(async () => {
    const started = await integrationService.startSync(fullResync);
    return waitForSyncRun(started.id);
  });

  const runSync = async () => {
    const run = await sync.run();
    if (!run) {
      return; // the dialog shows the error
    }
    setSyncOpen(false);
    const counts = `${run.invoicesCreated} new, ${run.invoicesUpdated} updated, ${run.invoicesUnchanged} unchanged, ${run.invoicesRejected} rejected`;
    if (run.status === 'COMPLETED') {
      toast.success(`Synchronization completed: ${counts}.`);
    } else {
      toast.error(`Synchronization ${run.status.toLowerCase()}: ${counts}. ${run.errorMessage ?? ''}`);
    }
    table.reload();
  };

  const set = (patch: Partial<InvoiceFilter>) => setFilter((current) => ({ ...current, ...patch }));
  const active = Object.values(filter).some((value) => value !== undefined && value !== '');

  return (
    <>
      <PageHeader
        title="Invoices"
        subtitle="Vendor bills synchronized from Odoo. Posted bills only."
        actions={
          hasRole(...OPERATIONS) ? (
            <button type="button" className="btn-primary" onClick={() => { sync.clearError(); setFullResync(false); setSyncOpen(true); }}>
              Sync from Odoo
            </button>
          ) : undefined
        }
      />

      <Card>
        <FilterBar onReset={() => setFilter(EMPTY)} active={active}>
          <Field label="Invoice number" className="w-48">
            <input className="input" type="search" placeholder="Contains…" value={filter.invoiceNumber ?? ''} onChange={(event) => set({ invoiceNumber: event.target.value })} />
          </Field>
          <Field label="Supplier" className="w-56">
            <select className="input" value={filter.supplierId ?? ''} onChange={(event) => set({ supplierId: event.target.value })}>
              <option value="">All suppliers</option>
              {(suppliers.data?.items ?? []).map((supplier) => (
                <option key={supplier.id} value={supplier.id}>
                  {supplier.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Payment status" className="w-48">
            <select className="input" value={filter.status ?? ''} onChange={(event) => set({ status: event.target.value })}>
              <option value="">Any status</option>
              {INVOICE_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status.replace(/_/g, ' ').toLowerCase()}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Invoiced from" className="w-40">
            <input className="input" type="date" value={filter.dateFrom ?? ''} onChange={(event) => set({ dateFrom: event.target.value })} />
          </Field>
          <Field label="Invoiced to" className="w-40">
            <input className="input" type="date" value={filter.dateTo ?? ''} onChange={(event) => set({ dateTo: event.target.value })} />
          </Field>
        </FilterBar>
        <DataTable
          caption="Invoices"
          columns={COLUMNS}
          rows={table.items}
          rowKey={(row) => row.id}
          loading={table.loading}
          error={table.error}
          onRetry={table.reload}
          emptyTitle={active ? 'No invoice matches these filters' : 'No invoices yet'}
          emptyHint={active ? undefined : 'Invoices appear here after an Odoo synchronization.'}
          meta={table.meta}
          onPage={table.setPage}
          sort={table.sort}
          onSort={table.toggleSort}
          onRowClick={(row) => navigate(`/invoices/${row.id}`)}
        />
      </Card>

      {syncOpen && (
        <ConfirmDialog
          title="Synchronize from Odoo"
          confirmLabel="Start synchronization"
          onConfirm={() => void runSync()}
          onCancel={() => setSyncOpen(false)}
          running={sync.running}
          error={sync.error}
        >
          <p>
            FinBridge reads suppliers and posted vendor bills from Odoo. Nothing is written to Odoo, and a bill that
            is already here is updated, never duplicated.
          </p>
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-0.5" checked={fullResync} onChange={(event) => setFullResync(event.target.checked)} disabled={sync.running} />
            <span>
              Read everything again, not only what changed since the last run.
              <span className="block text-xs text-slate-500">Slower. Unchanged bills are reported as unchanged.</span>
            </span>
          </label>
          {sync.running && <p className="text-slate-500">Synchronizing. This window closes when the run has finished.</p>}
        </ConfirmDialog>
      )}
    </>
  );
}
