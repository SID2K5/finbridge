import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { EmptyState, PageState } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, DefinitionList, PageHeader } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useQuery } from '../hooks/useQuery';
import { date, dateTime, label, money, newIdempotencyKey, paymentOutcome } from '../lib/format';
import { FINANCE } from '../lib/roles';
import { invoiceService } from '../services/invoices';
import { paymentService } from '../services/payments';

export function InvoiceDetailPage() {
  const id = Number(useParams().id);
  const toast = useToast();
  const { hasRole } = useAuth();
  const invoice = useQuery(() => invoiceService.get(id), [id]);
  const history = useQuery(() => invoiceService.history(id), [id]);

  // The key is created ONCE, when the Pay dialog opens, and reused for every click and
  // retry inside that dialog. Two clicks therefore cannot become two payments.
  const [payKey, setPayKey] = useState<string | null>(null);
  const pay = useAsyncAction((key: string) => paymentService.create(id, key));

  const data = invoice.data;
  const payable = !!data && (data.invoiceStatus === 'OPEN' || data.invoiceStatus === 'PAYMENT_FAILED') && data.syncStatus !== 'STALE';

  const confirmPay = async () => {
    if (!payKey) {
      return;
    }
    const result = await pay.run(payKey);
    if (!result) {
      return;
    }
    setPayKey(null);
    const { payment } = result;
    const text = paymentOutcome(payment.transactionReference, payment.status);
    if (payment.status === 'SUCCESS') {
      toast.success(text);
    } else if (payment.status === 'FAILED' || payment.status === 'REJECTED') {
      toast.error(`${text} ${payment.failureReason ?? ''}`);
    } else {
      toast.info(`${text} An operator can check its status from the payment page.`);
    }
    invoice.reload();
  };

  return (
    <>
      <PageHeader
        title={data ? `Invoice ${data.invoiceNumber}` : 'Invoice'}
        subtitle={<Link to="/invoices" className="text-navy-700 underline">All invoices</Link>}
        actions={
          data && hasRole(...FINANCE) ? (
            <button
              type="button"
              className="btn-primary"
              disabled={!payable}
              title={payable ? undefined : 'Only an open invoice, or one whose payment failed, can be paid'}
              onClick={() => { pay.clearError(); setPayKey(newIdempotencyKey()); }}
            >
              Pay invoice
            </button>
          ) : undefined
        }
      />

      <PageState loading={invoice.loading} error={invoice.error} onRetry={invoice.reload}>
        {data && (
          <div className="space-y-5">
            {data.syncStatus === 'STALE' && (
              <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900" role="alert">
                This bill changed in Odoo after FinBridge started paying it. FinBridge kept its own amounts; review the
                bill in Odoo and the reconciliation before doing anything else with it.
              </div>
            )}
            <Card title="Invoice">
              <DefinitionList
                items={[
                  { term: 'Payment status', value: <StatusBadge status={data.invoiceStatus} /> },
                  { term: 'Total', value: <span className="font-semibold">{money(data.totalAmount, data.currency)}</span> },
                  { term: 'Untaxed / tax', value: `${money(data.untaxedAmount)} / ${money(data.taxAmount)}` },
                  { term: 'Invoice date', value: date(data.invoiceDate) },
                  { term: 'Due date', value: date(data.dueDate) },
                  { term: 'Vendor reference', value: data.vendorReference },
                  { term: 'Purchase order', value: data.poReference },
                  { term: 'Sync status', value: <StatusBadge status={data.syncStatus} /> },
                  { term: 'Odoo record', value: `account.move ${data.odooMoveId}` },
                  { term: 'Payment state in Odoo', value: data.odooPaymentState ? label(data.odooPaymentState) : undefined },
                  { term: 'Last synchronized', value: dateTime(data.lastSyncedAt) },
                ]}
              />
            </Card>

            <Card title="Supplier">
              <DefinitionList
                items={[
                  { term: 'Name', value: data.supplier.name },
                  { term: 'Tax id', value: data.supplier.taxId },
                  { term: 'Email', value: data.supplier.email },
                  { term: 'Phone', value: data.supplier.phone },
                  { term: 'Country', value: data.supplier.countryCode },
                  { term: 'Odoo partner', value: `res.partner ${data.supplier.odooPartnerId}${data.supplier.active ? '' : ' (archived)'}` },
                ]}
              />
            </Card>

            <Card title="Payments">
              {data.payments.length === 0 ? (
                <EmptyState title="No payment has been made for this invoice" />
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full divide-y divide-slate-200">
                    <caption className="sr-only">Payments of this invoice</caption>
                    <thead className="bg-slate-50">
                      <tr>
                        <th className="th" scope="col">Reference</th>
                        <th className="th" scope="col">Status</th>
                        <th className="th text-right" scope="col">Requested</th>
                        <th className="th text-right" scope="col">Processed</th>
                        <th className="th text-right" scope="col">Attempts</th>
                        <th className="th" scope="col">Requested at</th>
                        <th className="th" scope="col">Reason</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {data.payments.map((payment) => (
                        <tr key={payment.id}>
                          <td className="td">
                            <Link to={`/payments/${payment.id}`} className="font-mono text-xs font-medium text-navy-700 underline">
                              {payment.transactionReference}
                            </Link>
                          </td>
                          <td className="td"><StatusBadge status={payment.status} /></td>
                          <td className="td whitespace-nowrap text-right tabular-nums">{money(payment.requestedAmount, payment.currency)}</td>
                          <td className="td whitespace-nowrap text-right tabular-nums">{money(payment.processedAmount, payment.currency)}</td>
                          <td className="td text-right tabular-nums">{payment.attemptCount}</td>
                          <td className="td whitespace-nowrap">{dateTime(payment.requestedAt)}</td>
                          <td className="td text-xs text-slate-600">{payment.failureReason ?? '–'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <Card title="Synchronization history">
              <PageState
                loading={history.loading}
                error={history.error}
                onRetry={history.reload}
                empty={(history.data?.items.length ?? 0) === 0}
                emptyTitle="No synchronization history"
                emptyHint="Demo rows loaded by the dev profile were not synchronized from Odoo, so they have none."
              >
                <ul className="divide-y divide-slate-100">
                  {(history.data?.items ?? []).map((entry) => (
                    <li key={entry.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2.5 text-sm">
                      <span className="w-44 whitespace-nowrap text-slate-600">{dateTime(entry.createdAt)}</span>
                      <StatusBadge status={entry.action} tone={entry.action === 'CREATED' || entry.action === 'UPDATED' ? 'green' : undefined} />
                      <span className="text-slate-500">sync run {entry.syncRunId}</span>
                      {entry.message && <span className="text-slate-700">{entry.message}</span>}
                    </li>
                  ))}
                </ul>
              </PageState>
            </Card>
          </div>
        )}
      </PageState>

      {payKey && data && (
        <ConfirmDialog
          title="Pay this invoice"
          confirmLabel={`Pay ${money(data.totalAmount, data.currency)}`}
          onConfirm={() => void confirmPay()}
          onCancel={() => setPayKey(null)}
          running={pay.running}
          error={pay.error}
        >
          <p>
            A payment instruction for the full amount of <strong>{money(data.totalAmount, data.currency)}</strong> will be
            sent to the payment service for <strong>{data.supplier.name}</strong>, invoice <strong>{data.invoiceNumber}</strong>.
          </p>
          <p className="text-slate-500">
            This cannot be undone from FinBridge. If the answer is lost on the way, the payment is shown as
            "Timeout unknown" and is never sent twice.
          </p>
          <p className="font-mono text-xs text-slate-400">Idempotency key {payKey}</p>
        </ConfirmDialog>
      )}
    </>
  );
}
