import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DataTable, type Column } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';
import { Card, Field, FilterBar, PageHeader } from '../components/ui';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { dateTime, money } from '../lib/format';
import { paymentService, type PaymentFilter } from '../services/payments';
import type { Payment } from '../types/api';

const STATUSES = ['SUCCESS', 'PENDING', 'TIMEOUT_UNKNOWN', 'FAILED', 'REJECTED', 'SENT', 'CREATED'];
const EMPTY: PaymentFilter = {};

const COLUMNS: Column<Payment>[] = [
  { key: 'reference', header: 'Reference', sortField: 'transactionReference', render: (row) => <span className="font-mono text-xs font-medium text-navy-800">{row.transactionReference}</span> },
  { key: 'invoice', header: 'Invoice', render: (row) => row.invoiceNumber },
  { key: 'supplier', header: 'Supplier', render: (row) => row.supplierName },
  { key: 'status', header: 'Status', sortField: 'status', render: (row) => <StatusBadge status={row.status} /> },
  { key: 'requested', header: 'Requested', sortField: 'requestedAmount', align: 'right', className: 'whitespace-nowrap', render: (row) => money(row.requestedAmount, row.currency) },
  { key: 'processed', header: 'Processed', align: 'right', className: 'whitespace-nowrap', render: (row) => money(row.processedAmount, row.currency) },
  { key: 'attempts', header: 'Attempts', align: 'right', render: (row) => row.attemptCount },
  { key: 'at', header: 'Requested at', sortField: 'requestedAt', className: 'whitespace-nowrap', render: (row) => dateTime(row.requestedAt) },
];

export function PaymentsPage() {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<PaymentFilter>(EMPTY);
  const table = usePagedQuery(
    (page, size, sort) => paymentService.list(filter, page, size, sort),
    JSON.stringify(filter),
  );
  const set = (patch: Partial<PaymentFilter>) => setFilter((current) => ({ ...current, ...patch }));
  const active = Object.values(filter).some((value) => value !== undefined && value !== '');

  return (
    <>
      <PageHeader title="Payments" subtitle="Every payment instruction sent to the payment service, newest first." />
      <Card>
        <FilterBar onReset={() => setFilter(EMPTY)} active={active}>
          <Field label="Reference" className="w-56">
            <input className="input" type="search" placeholder="FB-… or SIM-…" value={filter.reference ?? ''} onChange={(event) => set({ reference: event.target.value })} />
          </Field>
          <Field label="Status" className="w-48">
            <select className="input" value={filter.status ?? ''} onChange={(event) => set({ status: event.target.value })}>
              <option value="">Any status</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status.replace(/_/g, ' ').toLowerCase()}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Requested from" className="w-40">
            <input className="input" type="date" value={filter.dateFrom ?? ''} onChange={(event) => set({ dateFrom: event.target.value })} />
          </Field>
          <Field label="Requested to" className="w-40">
            <input className="input" type="date" value={filter.dateTo ?? ''} onChange={(event) => set({ dateTo: event.target.value })} />
          </Field>
        </FilterBar>
        <DataTable
          caption="Payments"
          columns={COLUMNS}
          rows={table.items}
          rowKey={(row) => row.id}
          loading={table.loading}
          error={table.error}
          onRetry={table.reload}
          emptyTitle={active ? 'No payment matches these filters' : 'No payments yet'}
          emptyHint={active ? undefined : 'A finance manager pays an invoice from the invoice page.'}
          meta={table.meta}
          onPage={table.setPage}
          sort={table.sort}
          onSort={table.toggleSort}
          onRowClick={(row) => navigate(`/payments/${row.id}`)}
        />
      </Card>
    </>
  );
}
