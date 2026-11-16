import { Link } from 'react-router-dom';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { EmptyState, PageState } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, KpiTile, PageHeader } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useQuery } from '../hooks/useQuery';
import { dateTime, label, moneyList } from '../lib/format';
import { FINANCE, OPERATIONS } from '../lib/roles';
import { dashboardService } from '../services/dashboard';

// The same fixed status colours as the badges.
const STATUS_COLOURS: Record<string, string> = {
  SUCCESS: '#10b981',
  PENDING: '#f59e0b',
  TIMEOUT_UNKNOWN: '#f59e0b',
  FAILED: '#ef4444',
  REJECTED: '#ef4444',
  SENT: '#0ea5e9',
  CREATED: '#94a3b8',
};

/** Every figure on this page comes from the backend at load time. With no data a tile says so. */
export function DashboardPage() {
  const { hasRole } = useAuth();
  const summary = useQuery(() => dashboardService.summary(), []);
  const transactions = useQuery(() => dashboardService.transactions(), []);
  const trend = useQuery(() => dashboardService.invoiceTrend(30), []);
  const activity = useQuery(() => dashboardService.recentActivity(), []);

  const data = summary.data;
  const hasInvoices = !!data && data.invoiceCount > 0;
  const trendTotal = trend.data?.reduce((sum, day) => sum + day.count, 0) ?? 0;

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Live figures from FinBridge's own records." />

      <PageState loading={summary.loading} error={summary.error} onRetry={summary.reload}>
        {data && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
            <KpiTile
              label="Invoices synchronized"
              value={hasInvoices ? data.invoiceCount : null}
              emptyText="None yet. Run an Odoo synchronization."
              hint={data.lastSync ? `Last sync ${dateTime(data.lastSync.finishedAt ?? data.lastSync.startedAt)}, ${label(data.lastSync.status).toLowerCase()}` : 'No synchronization has run yet'}
            />
            <KpiTile label="Total invoice value" value={moneyList(data.invoiceValue)} emptyText="No invoices yet" />
            <KpiTile
              label="Open payable"
              value={hasInvoices ? moneyList(data.openPayable) ?? 'Nothing open' : null}
              hint="Invoices that are open or whose payment failed"
              emptyText="No invoices yet"
            />
            <KpiTile
              label="Paid through FinBridge"
              value={moneyList(data.paidAmount)}
              tone="good"
              hint={data.paymentCount > 0 ? `${data.paymentsByStatus.SUCCESS ?? 0} successful of ${data.paymentCount} payments` : undefined}
              emptyText="No successful payment yet"
            />
            <KpiTile
              label="Payments needing attention"
              value={data.paymentCount > 0 ? data.paymentsNeedingAttention : null}
              tone={data.paymentsNeedingAttention > 0 ? 'attention' : 'default'}
              hint="Pending, timed out or failed"
              emptyText="No payments yet"
            />
            <KpiTile
              label="Open discrepancies"
              value={data.openDiscrepancies ?? null}
              tone={(data.openDiscrepancies ?? 0) > 0 ? 'attention' : 'default'}
              hint={
                data.latestReconciliationRunId !== undefined ? (
                  hasRole(...FINANCE) ? (
                    <Link to="/reconciliation" className="text-navy-700 underline">
                      From reconciliation run {data.latestReconciliationRunId}
                    </Link>
                  ) : (
                    `From reconciliation run ${data.latestReconciliationRunId}`
                  )
                ) : undefined
              }
              emptyText="No reconciliation has run yet"
            />
          </div>
        )}
      </PageState>

      <div className="mt-5 grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card title="Payments by status">
          <PageState
            loading={transactions.loading}
            error={transactions.error}
            onRetry={transactions.reload}
            empty={(transactions.data?.length ?? 0) === 0}
            emptyTitle="No payments yet"
            emptyHint="Payments appear here once an invoice has been paid."
          >
            <div className="h-64 px-2 py-4">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={(transactions.data ?? []).map((row) => ({ ...row, name: label(row.status) }))} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" vertical={false} />
                  <XAxis dataKey="name" tick={{ fontSize: 11, fill: '#64748b' }} interval={0} />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: '#64748b' }} width={32} />
                  <Tooltip cursor={{ fill: '#f1f5f9' }} formatter={(value: number) => [value, 'Payments']} />
                  <Bar dataKey="count" radius={[3, 3, 0, 0]} maxBarSize={56} isAnimationActive={false}>
                    {(transactions.data ?? []).map((row) => (
                      <Cell key={row.status} fill={STATUS_COLOURS[row.status] ?? '#94a3b8'} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </PageState>
        </Card>

        <Card title="Invoices synchronized per day, last 30 days">
          <PageState
            loading={trend.loading}
            error={trend.error}
            onRetry={trend.reload}
            empty={trendTotal === 0}
            emptyTitle="No invoice was synchronized in the last 30 days"
          >
            <div className="h-64 px-2 py-4">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={trend.data ?? []} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" vertical={false} />
                  <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#64748b' }} tickFormatter={(value: string) => value.slice(5)} minTickGap={24} />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: '#64748b' }} width={32} />
                  <Tooltip formatter={(value: number) => [value, 'Invoices']} />
                  <Area type="monotone" dataKey="count" stroke="#1f3760" fill="#c3d0e3" strokeWidth={2} isAnimationActive={false} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </PageState>
        </Card>
      </div>

      <Card
        title="Recent integration activity"
        className="mt-5"
        actions={
          hasRole(...OPERATIONS) ? (
            <Link to="/integrations/logs" className="text-sm font-medium text-navy-700 underline">
              All logs
            </Link>
          ) : undefined
        }
      >
        <PageState loading={activity.loading} error={activity.error} onRetry={activity.reload}>
          {(activity.data?.length ?? 0) === 0 ? (
            <EmptyState title="No call to an external system yet" hint="Odoo, SOAP and REST calls are listed here as they happen." />
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200">
                <caption className="sr-only">The latest twenty calls to external systems</caption>
                <thead className="bg-slate-50">
                  <tr>
                    <th className="th" scope="col">When</th>
                    <th className="th" scope="col">System</th>
                    <th className="th" scope="col">Operation</th>
                    <th className="th" scope="col">Outcome</th>
                    <th className="th text-right" scope="col">Duration</th>
                    <th className="th" scope="col">Reference</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(activity.data ?? []).map((row) => (
                    <tr key={row.id}>
                      <td className="td whitespace-nowrap">{dateTime(row.at)}</td>
                      <td className="td">{row.type}</td>
                      <td className="td font-mono text-xs">{row.operation}</td>
                      <td className="td">
                        <StatusBadge status={row.outcome} />
                        {row.errorCode && <span className="ml-2 text-xs text-slate-500">{row.errorCode}</span>}
                      </td>
                      <td className="td text-right tabular-nums">{row.durationMs} ms</td>
                      <td className="td font-mono text-xs">{row.reference ?? '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </PageState>
      </Card>
    </>
  );
}
