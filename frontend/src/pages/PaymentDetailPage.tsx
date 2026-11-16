import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { EmptyState, Loading, PageState } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, DefinitionList, InlineError, Modal, PageHeader } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useQuery } from '../hooks/useQuery';
import { dateTime, money, paymentOutcome } from '../lib/format';
import { OPERATIONS } from '../lib/roles';
import { integrationService } from '../services/integrations';
import { paymentService } from '../services/payments';
import type { Payment } from '../types/api';

type PendingAction = 'status-check' | 'retry';

const EXPLANATIONS: Record<string, string> = {
  TIMEOUT_UNKNOWN:
    'The request was sent and no answer came back. The payment may or may not have been made. FinBridge will not send it again until the payment service has been asked what happened.',
  PENDING: 'The payment service accepted the payment and has not settled it yet. A status check asks for the current state.',
  FAILED: 'The payment was not made. An operator may send it again, up to the attempt limit.',
  REJECTED: 'The payment service refused this payment. That is final; a new payment can be created from the invoice.',
};

export function PaymentDetailPage() {
  const id = Number(useParams().id);
  const toast = useToast();
  const { hasRole } = useAuth();
  const canOperate = hasRole(...OPERATIONS);
  const detail = useQuery(() => paymentService.get(id), [id]);
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [xmlLogId, setXmlLogId] = useState<number | null>(null);

  const action = useAsyncAction((kind: PendingAction): Promise<Payment> =>
    kind === 'retry' ? paymentService.retry(id) : paymentService.statusCheck(id),
  );

  const payment = detail.data?.payment;
  const canCheck = !!payment && (payment.status === 'PENDING' || payment.status === 'TIMEOUT_UNKNOWN');
  const canRetry = !!payment && (payment.status === 'FAILED' || payment.status === 'TIMEOUT_UNKNOWN');

  const confirm = async () => {
    if (!pending) {
      return;
    }
    const result = await action.run(pending);
    if (!result) {
      return;
    }
    setPending(null);
    const text = paymentOutcome(result.transactionReference, result.status);
    if (result.status === 'SUCCESS') {
      toast.success(text);
    } else if (result.status === 'FAILED' || result.status === 'REJECTED') {
      toast.error(`${text} ${result.failureReason ?? ''}`);
    } else {
      toast.info(text);
    }
    detail.reload();
  };

  return (
    <>
      <PageHeader
        title={payment ? `Payment ${payment.transactionReference}` : 'Payment'}
        subtitle={<Link to="/payments" className="text-navy-700 underline">All payments</Link>}
        actions={
          payment && canOperate ? (
            <>
              <button type="button" className="btn-secondary" disabled={!canCheck} onClick={() => { action.clearError(); setPending('status-check'); }}>
                Check status
              </button>
              <button type="button" className="btn-primary" disabled={!canRetry} onClick={() => { action.clearError(); setPending('retry'); }}>
                Retry
              </button>
            </>
          ) : undefined
        }
      />

      <PageState loading={detail.loading} error={detail.error} onRetry={detail.reload}>
        {payment && detail.data && (
          <div className="space-y-5">
            {EXPLANATIONS[payment.status] && (
              <div
                className={`rounded-md border px-4 py-3 text-sm ${
                  payment.status === 'FAILED' || payment.status === 'REJECTED'
                    ? 'border-red-200 bg-red-50 text-red-900'
                    : 'border-amber-200 bg-amber-50 text-amber-900'
                }`}
              >
                {EXPLANATIONS[payment.status]}
                {!canOperate && (payment.status === 'TIMEOUT_UNKNOWN' || payment.status === 'PENDING' || payment.status === 'FAILED') && (
                  <span> Status checks and retries are done by an integration operator or an administrator.</span>
                )}
              </div>
            )}

            <Card title="Transaction">
              <DefinitionList
                items={[
                  { term: 'Status', value: <StatusBadge status={payment.status} /> },
                  { term: 'Requested', value: <span className="font-semibold">{money(payment.requestedAmount, payment.currency)}</span> },
                  { term: 'Processed', value: money(payment.processedAmount, payment.currency) },
                  { term: 'Invoice', value: <Link to={`/invoices/${payment.invoiceId}`} className="text-navy-700 underline">{payment.invoiceNumber}</Link> },
                  { term: 'Supplier', value: payment.supplierName },
                  { term: 'Attempts', value: payment.attemptCount },
                  { term: 'External transaction id', value: payment.externalTransactionId ? <span className="font-mono text-xs">{payment.externalTransactionId}</span> : undefined },
                  { term: 'Requested at', value: dateTime(payment.requestedAt) },
                  { term: 'Last attempt', value: dateTime(payment.lastAttemptAt) },
                  { term: 'Answered at', value: dateTime(payment.respondedAt) },
                  { term: 'Reason', value: payment.failureReason },
                ]}
              />
            </Card>

            <Card title="Calls to the payment service">
              {detail.data.attempts.length === 0 ? (
                <EmptyState title="No call has been recorded for this payment" />
              ) : (
                <ol className="divide-y divide-slate-100">
                  {detail.data.attempts.map((attempt, index) => (
                    <li key={attempt.logId} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-sm">
                      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-navy-100 text-xs font-semibold text-navy-800">{index + 1}</span>
                      <span className="w-44 whitespace-nowrap text-slate-600">{dateTime(attempt.at)}</span>
                      <span className="w-40 font-mono text-xs">{attempt.operation}</span>
                      <StatusBadge status={attempt.outcome} />
                      <span className="tabular-nums text-slate-500">{attempt.durationMs} ms</span>
                      {attempt.errorCode && <span className="text-xs text-red-700">{attempt.errorCode}</span>}
                      {canOperate && (
                        <button type="button" className="ml-auto text-sm font-medium text-navy-700 underline" onClick={() => setXmlLogId(attempt.logId)}>
                          View SOAP messages
                        </button>
                      )}
                      {attempt.errorDescription && <p className="basis-full pl-10 text-xs text-slate-600">{attempt.errorDescription}</p>}
                    </li>
                  ))}
                </ol>
              )}
            </Card>
          </div>
        )}
      </PageState>

      {pending && payment && (
        <ConfirmDialog
          title={pending === 'retry' ? 'Retry this payment' : 'Check the payment status'}
          confirmLabel={pending === 'retry' ? 'Retry payment' : 'Check status'}
          onConfirm={() => void confirm()}
          onCancel={() => setPending(null)}
          running={action.running}
          error={action.error}
        >
          {pending === 'retry' && payment.status === 'FAILED' && (
            <p>
              The payment of <strong>{money(payment.requestedAmount, payment.currency)}</strong> for invoice{' '}
              <strong>{payment.invoiceNumber}</strong> will be sent again as attempt {payment.attemptCount + 1}.
            </p>
          )}
          {pending === 'retry' && payment.status === 'TIMEOUT_UNKNOWN' && (
            <p>
              FinBridge first asks the payment service what happened to this payment. It is sent again only if the
              service never received it, so it cannot be paid twice.
            </p>
          )}
          {pending === 'status-check' && <p>FinBridge asks the payment service for the current state of this payment and records the answer.</p>}
        </ConfirmDialog>
      )}

      {xmlLogId !== null && <SoapMessages logId={xmlLogId} onClose={() => setXmlLogId(null)} />}
    </>
  );
}

/** The request and response XML of one call, as stored (masked and size-capped by the backend). */
function SoapMessages({ logId, onClose }: { logId: number; onClose: () => void }) {
  const log = useQuery(() => integrationService.log(logId), [logId]);
  return (
    <Modal
      title={`SOAP messages of call ${logId}`}
      onClose={onClose}
      wide
      footer={
        <button type="button" className="btn-secondary" onClick={onClose}>
          Close
        </button>
      }
    >
      {log.loading && <Loading />}
      <InlineError error={log.error} />
      {log.data && (
        <>
          <XmlBlock title="Request" xml={log.data.requestPayload} />
          <XmlBlock title="Response" xml={log.data.responsePayload} />
          <p className="font-mono text-xs text-slate-400">Correlation id {log.data.correlationId}</p>
        </>
      )}
    </Modal>
  );
}

function XmlBlock({ title, xml }: { title: string; xml?: string }) {
  return (
    <div>
      <p className="label">{title}</p>
      {xml ? (
        // Rendered as text: React escapes it, so XML from outside can never become markup.
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-md bg-slate-900 p-3 font-mono text-xs leading-relaxed text-slate-100">
          {xml.replace(/></g, '>\n<')}
        </pre>
      ) : (
        <p className="text-sm italic text-slate-400">Nothing was received.</p>
      )}
    </div>
  );
}
