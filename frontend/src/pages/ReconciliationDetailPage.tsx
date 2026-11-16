import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageState } from '../components/PageState';
import { ReviewBadge, StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, DefinitionList, Field, PageHeader } from '../components/ui';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useQuery } from '../hooks/useQuery';
import { dateTime, label, money } from '../lib/format';
import { reconciliationService } from '../services/reconciliation';

const FIELD_NAMES: Record<string, string> = {
  transactionReference: 'Transaction reference',
  invoiceReference: 'Invoice reference',
  supplierReference: 'Supplier reference',
  currency: 'Currency',
  amount: 'Amount (requested / settled)',
  status: 'Status',
  externalTransactionId: 'Payment service id',
  timestamp: 'Time (requested / answered)',
};

type Decision = 'RESOLVED' | 'NEEDS_INVESTIGATION';

export function ReconciliationDetailPage() {
  const id = Number(useParams().id);
  const toast = useToast();
  const detail = useQuery(() => reconciliationService.record(id), [id]);
  const [decision, setDecision] = useState<Decision>('RESOLVED');
  const [note, setNote] = useState('');
  const [confirming, setConfirming] = useState(false);
  const review = useAsyncAction(() => reconciliationService.review(id, decision, note.trim()));

  const data = detail.data;
  const record = data?.record;
  const reviewable = !!record && record.reviewStatus !== 'NOT_REQUIRED';

  const submit = async () => {
    const result = await review.run();
    if (!result) {
      return;
    }
    setConfirming(false);
    setNote('');
    toast.success(`Record ${id} is marked ${label(result.record.reviewStatus).toLowerCase()}.`);
    detail.reload();
  };

  return (
    <>
      <PageHeader
        title={`Reconciliation record ${id}`}
        subtitle={
          record ? (
            <Link to={`/reconciliation?run=${record.runId}`} className="text-navy-700 underline">
              Run {record.runId}
            </Link>
          ) : undefined
        }
      />

      <PageState loading={detail.loading} error={detail.error} onRetry={detail.reload}>
        {data && record && (
          <div className="space-y-5">
            <Card title="Finding">
              <DefinitionList
                items={[
                  { term: 'Result', value: <StatusBadge status={record.status} /> },
                  { term: 'Review', value: <ReviewBadge status={record.reviewStatus} /> },
                  { term: 'Reconciled at', value: dateTime(record.reconciledAt) },
                  { term: 'Expected amount', value: money(record.expectedAmount, record.currency) },
                  { term: 'Actual amount', value: money(record.actualAmount, record.currency) },
                  {
                    term: 'Linked records',
                    value: (
                      <span className="space-x-3">
                        {record.invoiceId && <Link to={`/invoices/${record.invoiceId}`} className="text-navy-700 underline">Invoice</Link>}
                        {record.paymentTransactionId && <Link to={`/payments/${record.paymentTransactionId}`} className="text-navy-700 underline">Payment</Link>}
                        {!record.invoiceId && !record.paymentTransactionId && 'None in FinBridge'}
                      </span>
                    ),
                  },
                ]}
              />
              {record.discrepancyDescription && (
                <p className="border-t border-slate-200 px-4 py-3 text-sm text-slate-800">{record.discrepancyDescription}</p>
              )}
            </Card>

            <Card title="Side by side">
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200">
                  <caption className="sr-only">FinBridge's values next to the payment service's values</caption>
                  <thead className="bg-slate-50">
                    <tr>
                      <th className="th" scope="col">Field</th>
                      <th className="th" scope="col">FinBridge</th>
                      <th className="th" scope="col">Payment service</th>
                      <th className="th" scope="col">Compared</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {data.comparison.map((row) => (
                      <tr key={row.field} className={row.matches === false ? 'bg-red-50' : undefined}>
                        <th scope="row" className="td text-left font-medium">{FIELD_NAMES[row.field] ?? row.field}</th>
                        <td className="td font-mono text-xs">{row.internal ?? <span className="font-sans italic text-slate-400">no record</span>}</td>
                        <td className="td font-mono text-xs">{row.external ?? <span className="font-sans italic text-slate-400">no record</span>}</td>
                        <td className="td text-xs">
                          {row.matches === true && <span className="font-medium text-emerald-700">Same</span>}
                          {row.matches === false && <span className="font-semibold text-red-700">Different</span>}
                          {row.matches === undefined && <span className="text-slate-400">Not compared</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card title="Review">
              {record.reviewedAt && (
                <div className="border-b border-slate-200 px-4 py-3 text-sm">
                  <p className="text-slate-600">
                    Last reviewed {dateTime(record.reviewedAt)} by user {data.reviewedBy}:
                  </p>
                  <p className="mt-1 whitespace-pre-wrap text-slate-800">{data.reviewNote}</p>
                </div>
              )}
              {reviewable ? (
                <form
                  className="space-y-3 px-4 py-4"
                  onSubmit={(event) => {
                    event.preventDefault();
                    review.clearError();
                    setConfirming(true);
                  }}
                >
                  <fieldset>
                    <legend className="label">Decision</legend>
                    <div className="flex flex-wrap gap-4">
                      <label className="flex items-center gap-2">
                        <input type="radio" name="decision" checked={decision === 'RESOLVED'} onChange={() => setDecision('RESOLVED')} />
                        Resolved
                      </label>
                      <label className="flex items-center gap-2">
                        <input type="radio" name="decision" checked={decision === 'NEEDS_INVESTIGATION'} onChange={() => setDecision('NEEDS_INVESTIGATION')} />
                        Needs investigation
                      </label>
                    </div>
                  </fieldset>
                  <Field label="Note (required)">
                    <textarea className="input min-h-24" maxLength={1000} required value={note} onChange={(event) => setNote(event.target.value)} placeholder="What you found, and what was or will be done about it." />
                  </Field>
                  <button type="submit" className="btn-primary" disabled={note.trim().length === 0}>
                    Save review
                  </button>
                </form>
              ) : (
                <p className="px-4 py-4 text-sm text-slate-600">Both sides agree and the payment is final. No review is needed.</p>
              )}
            </Card>
          </div>
        )}
      </PageState>

      {confirming && (
        <ConfirmDialog
          title="Save this review"
          confirmLabel={decision === 'RESOLVED' ? 'Mark as resolved' : 'Mark as needing investigation'}
          onConfirm={() => void submit()}
          onCancel={() => setConfirming(false)}
          running={review.running}
          error={review.error}
        >
          <p>
            The record will be marked <strong>{label(decision).toLowerCase()}</strong> with your note. This records your
            decision only: it does not change the invoice or the payment.
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}
