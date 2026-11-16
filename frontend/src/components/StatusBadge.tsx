import { label } from '../lib/format';

/**
 * Status colours are fixed across the whole application, and every badge also carries its
 * text, so colour is never the only way to tell one status from another.
 *
 *   green  done and correct       amber  waiting or needs a look
 *   red    failed or a discrepancy slate  neutral
 */
type Tone = 'green' | 'amber' | 'red' | 'slate' | 'blue';

const TONES: Record<string, Tone> = {
  // payments
  SUCCESS: 'green',
  PENDING: 'amber',
  TIMEOUT_UNKNOWN: 'amber',
  FAILED: 'red',
  REJECTED: 'red',
  CREATED: 'slate',
  SENT: 'blue',
  // invoices
  OPEN: 'blue',
  PAYMENT_IN_PROGRESS: 'amber',
  PAID: 'green',
  PAYMENT_FAILED: 'red',
  SYNCED: 'slate',
  UPDATED: 'blue',
  STALE: 'red',
  // reconciliation
  MATCHED: 'green',
  PENDING_REVIEW: 'amber',
  MISMATCH: 'red',
  MISSING_EXTERNAL_RECORD: 'red',
  MISSING_INTERNAL_RECORD: 'red',
  DUPLICATE: 'red',
  NOT_REQUIRED: 'slate',
  RESOLVED: 'green',
  NEEDS_INVESTIGATION: 'amber',
  // runs, integrations, logs
  RUNNING: 'blue',
  COMPLETED: 'green',
  PARTIAL: 'amber',
  UP: 'green',
  DOWN: 'red',
  UNKNOWN: 'slate',
  NOT_CONFIGURED: 'amber',
  FAILURE: 'red',
  TIMEOUT: 'amber',
  REJECTED_INVALID: 'red',
  UNCHANGED: 'slate',
};

const CLASSES: Record<Tone, string> = {
  green: 'bg-emerald-50 text-emerald-800 ring-emerald-600/25',
  amber: 'bg-amber-50 text-amber-900 ring-amber-600/30',
  red: 'bg-red-50 text-red-800 ring-red-600/25',
  slate: 'bg-slate-100 text-slate-700 ring-slate-500/20',
  blue: 'bg-sky-50 text-sky-800 ring-sky-600/25',
};

const DOTS: Record<Tone, string> = {
  green: 'bg-emerald-500',
  amber: 'bg-amber-500',
  red: 'bg-red-500',
  slate: 'bg-slate-400',
  blue: 'bg-sky-500',
};

interface Props {
  status: string | null | undefined;
  /** For the one code that means different things in two places: a review that is OPEN needs attention. */
  tone?: Tone;
}

export function StatusBadge({ status, tone }: Props) {
  if (!status) {
    return <span className="text-slate-400">–</span>;
  }
  const resolved = tone ?? TONES[status] ?? 'slate';
  return (
    <span
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${CLASSES[resolved]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${DOTS[resolved]}`} aria-hidden="true" />
      {label(status)}
    </span>
  );
}

/** A review status: OPEN here means "waiting for a person", so it is amber, not the neutral blue of an open invoice. */
export function ReviewBadge({ status }: { status: string | null | undefined }) {
  return <StatusBadge status={status} tone={status === 'OPEN' ? 'amber' : undefined} />;
}
