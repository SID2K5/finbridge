// TypeScript mirrors of the backend's DTOs. Optional fields are the ones the backend leaves
// out when they are null (it serializes with "non_null").

export type Role = 'ADMIN' | 'FINANCE_MANAGER' | 'INTEGRATION_OPERATOR';

export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Page<T> {
  items: T[];
  meta: PageMeta;
}

export interface User {
  id: number;
  email: string;
  fullName: string;
  enabled: boolean;
  roles: Role[];
  lastLoginAt?: string;
  lockedUntil?: string;
  createdAt: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Supplier {
  id: number;
  odooPartnerId: number;
  name: string;
  taxId?: string;
  email?: string;
  phone?: string;
  countryCode?: string;
  active: boolean;
}

export interface InvoiceSummary {
  id: number;
  invoiceNumber: string;
  supplierId: number;
  supplierName: string;
  invoiceDate: string;
  dueDate?: string;
  totalAmount: number;
  currency: string;
  invoiceStatus: string;
  syncStatus: string;
}

export interface InvoicePayment {
  id: number;
  transactionReference: string;
  status: string;
  requestedAmount: number;
  processedAmount?: number;
  currency: string;
  failureReason?: string;
  attemptCount: number;
  requestedAt: string;
  respondedAt?: string;
}

export interface InvoiceDetail {
  id: number;
  odooMoveId: number;
  invoiceNumber: string;
  vendorReference?: string;
  poReference?: string;
  supplier: Supplier;
  invoiceDate: string;
  dueDate?: string;
  untaxedAmount: number;
  taxAmount: number;
  totalAmount: number;
  currency: string;
  invoiceStatus: string;
  odooPaymentState?: string;
  syncStatus: string;
  odooWriteDate: string;
  lastSyncedAt: string;
  payments: InvoicePayment[];
}

export interface SyncHistoryEntry {
  id: number;
  syncRunId: number;
  entityType: string;
  odooRecordId: number;
  action: string;
  message?: string;
  createdAt: string;
}

export interface SyncRun {
  id: number;
  triggerType: string;
  triggeredBy?: number;
  status: string;
  cursorFrom?: string;
  cursorTo?: string;
  suppliersUpserted: number;
  invoicesCreated: number;
  invoicesUpdated: number;
  invoicesUnchanged: number;
  invoicesRejected: number;
  errorMessage?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface SyncRunDetail {
  run: SyncRun;
  history: SyncHistoryEntry[];
}

export interface Payment {
  id: number;
  transactionReference: string;
  invoiceId: number;
  invoiceNumber: string;
  supplierId: number;
  supplierName: string;
  requestedAmount: number;
  currency: string;
  status: string;
  externalTransactionId?: string;
  processedAmount?: number;
  failureReason?: string;
  attemptCount: number;
  requestedAt: string;
  lastAttemptAt?: string;
  respondedAt?: string;
  initiatedBy: number;
}

export interface PaymentAttempt {
  logId: number;
  at: string;
  operation: string;
  outcome: string;
  httpStatus?: number;
  durationMs: number;
  errorCode?: string;
  errorDescription?: string;
}

export interface PaymentDetail {
  payment: Payment;
  attempts: PaymentAttempt[];
}

export interface ReconciliationRun {
  id: number;
  triggeredBy: number;
  periodFrom: string;
  periodTo: string;
  status: string;
  totalRecords: number;
  matchedCount: number;
  discrepancyCount: number;
  errorMessage?: string;
  startedAt: string;
  finishedAt?: string;
  countsByStatus?: Record<string, number>;
}

export interface ReconciliationRecord {
  id: number;
  runId: number;
  status: string;
  reviewStatus: string;
  invoiceId?: number;
  paymentTransactionId?: number;
  internalTransactionReference?: string;
  externalTransactionReference?: string;
  expectedAmount?: number;
  actualAmount?: number;
  currency?: string;
  discrepancyDescription?: string;
  reconciledAt: string;
  reviewedAt?: string;
}

export interface FieldComparison {
  field: string;
  internal?: string;
  external?: string;
  matches?: boolean;
}

export interface ReconciliationDetail {
  record: ReconciliationRecord;
  reviewNote?: string;
  reviewedBy?: number;
  comparison: FieldComparison[];
}

export interface LastError {
  at: string;
  operation: string;
  code?: string;
  message?: string;
}

export interface OdooStatus {
  mode: 'REAL' | 'MOCK';
  configured: boolean;
  state: string;
  baseUrl: string;
  database: string;
  lastCallAt?: string;
  lastError?: LastError;
  lastSync?: SyncRun;
}

export interface ServiceStatus {
  state: string;
  url: string;
  lastCallAt?: string;
  lastError?: LastError;
}

export interface IntegrationStatus {
  odoo: OdooStatus;
  soap: ServiceStatus;
  rest: ServiceStatus;
}

export interface CheckResult {
  target: string;
  ok: boolean;
  durationMs: number;
  errorCode?: string;
  message?: string;
}

export interface HealthCheck {
  odoo: CheckResult;
  soap: CheckResult;
  rest: CheckResult;
}

export interface IntegrationLogSummary {
  id: number;
  createdAt: string;
  type: string;
  operation: string;
  requestReference?: string;
  outcome: string;
  httpStatus?: number;
  durationMs: number;
  errorCode?: string;
  correlationId: string;
  retryable: boolean;
}

export interface IntegrationLogDetail extends IntegrationLogSummary {
  errorDescription?: string;
  invoiceId?: number;
  paymentTransactionId?: number;
  requestPayload?: string;
  responsePayload?: string;
}

export interface CurrencyAmount {
  currency: string;
  amount: number;
}

export interface DashboardSummary {
  invoiceCount: number;
  invoiceValue: CurrencyAmount[];
  openPayable: CurrencyAmount[];
  paidAmount: CurrencyAmount[];
  invoicesByStatus: Record<string, number>;
  paymentsByStatus: Record<string, number>;
  paymentCount: number;
  paymentsNeedingAttention: number;
  openDiscrepancies?: number;
  latestReconciliationRunId?: number;
  lastSync?: { runId: number; status: string; startedAt: string; finishedAt?: string };
}

export interface StatusCount {
  status: string;
  count: number;
  requestedAmount: number;
}

export interface DayCount {
  date: string;
  count: number;
}

export interface Activity {
  id: number;
  at: string;
  type: string;
  operation: string;
  outcome: string;
  durationMs: number;
  reference?: string;
  errorCode?: string;
}

export interface SettingView {
  key: string;
  value: string;
  defaultValue: string;
  min: number;
  max: number;
  description: string;
  overridden: boolean;
  updatedBy?: number;
  updatedAt?: string;
}

export interface ConfigView {
  settings: SettingView[];
  connections: { name: string; url: string; detail: string; connectTimeoutMs: number; readTimeoutMs: number }[];
  secrets: { name: string; purpose: string; configured: boolean }[];
}
