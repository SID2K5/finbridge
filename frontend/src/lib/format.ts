import type { CurrencyAmount, Role } from '../types/api';

const amountFormat = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const dateTimeFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/** "59,000.00 INR". A missing amount is shown as a dash, never as zero. */
export function money(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined) {
    return '–';
  }
  return currency ? `${amountFormat.format(amount)} ${currency}` : amountFormat.format(amount);
}

/** One line per currency; amounts in different currencies are never added together. */
export function moneyList(amounts: CurrencyAmount[] | undefined): string | null {
  if (!amounts || amounts.length === 0) {
    return null;
  }
  return amounts.map((entry) => money(entry.amount, entry.currency)).join(' · ');
}

/** A calendar date (yyyy-MM-dd) as "05 Nov 2026". */
export function date(value: string | null | undefined): string {
  if (!value) {
    return '–';
  }
  const parsed = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  return Number.isNaN(parsed.getTime()) ? value : dateFormat.format(parsed);
}

/** An instant in the viewer's own time zone. */
export function dateTime(value: string | null | undefined): string {
  if (!value) {
    return '–';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : dateTimeFormat.format(parsed);
}

/** "PAYMENT_IN_PROGRESS" as "Payment in progress". */
export function label(code: string | null | undefined): string {
  if (!code) {
    return '–';
  }
  const text = code.replace(/_/g, ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const PAYMENT_OUTCOMES: Record<string, string> = {
  SUCCESS: 'succeeded',
  FAILED: 'failed',
  REJECTED: 'was rejected',
  PENDING: 'is pending',
  TIMEOUT_UNKNOWN: 'got no answer, so its outcome is unknown',
  SENT: 'has been sent',
  CREATED: 'has been created',
};

/** "Payment FB-1 succeeded." */
export function paymentOutcome(reference: string, status: string): string {
  return `Payment ${reference} ${PAYMENT_OUTCOMES[status] ?? `is ${label(status).toLowerCase()}`}.`;
}

const ROLE_NAMES: Record<Role, string> = {
  ADMIN: 'Administrator',
  FINANCE_MANAGER: 'Finance Manager',
  INTEGRATION_OPERATOR: 'Integration Operator',
};

export function roleName(role: Role): string {
  return ROLE_NAMES[role] ?? role;
}

/** Today as yyyy-MM-dd in UTC, the time zone the backend's periods use. */
export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

/** A new idempotency key: a random UUID. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
