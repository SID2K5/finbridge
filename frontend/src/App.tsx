import { Link, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { RequireAuth, RequireRole } from './components/RequireRole';
import { ADMIN_ONLY, ALL, FINANCE, OPERATIONS } from './lib/roles';
import { DashboardPage } from './pages/DashboardPage';
import { IntegrationLogsPage } from './pages/IntegrationLogsPage';
import { IntegrationsPage } from './pages/IntegrationsPage';
import { InvoiceDetailPage } from './pages/InvoiceDetailPage';
import { InvoicesPage } from './pages/InvoicesPage';
import { LoginPage } from './pages/LoginPage';
import { PaymentDetailPage } from './pages/PaymentDetailPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { ReconciliationDetailPage } from './pages/ReconciliationDetailPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { SettingsPage } from './pages/SettingsPage';
import { UsersPage } from './pages/UsersPage';
import type { Role } from './types/api';

function guarded(roles: Role[], page: JSX.Element): JSX.Element {
  return <RequireRole roles={roles}>{page}</RequireRole>;
}

/** The twelve routes. Everything except /login sits inside the layout and needs a session. */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/" element={guarded(ALL, <DashboardPage />)} />
        <Route path="/invoices" element={guarded(ALL, <InvoicesPage />)} />
        <Route path="/invoices/:id" element={guarded(ALL, <InvoiceDetailPage />)} />
        <Route path="/payments" element={guarded(ALL, <PaymentsPage />)} />
        <Route path="/payments/:id" element={guarded(ALL, <PaymentDetailPage />)} />
        <Route path="/reconciliation" element={guarded(FINANCE, <ReconciliationPage />)} />
        <Route path="/reconciliation/:id" element={guarded(FINANCE, <ReconciliationDetailPage />)} />
        <Route path="/integrations" element={guarded(OPERATIONS, <IntegrationsPage />)} />
        <Route path="/integrations/logs" element={guarded(OPERATIONS, <IntegrationLogsPage />)} />
        <Route path="/admin/users" element={guarded(ADMIN_ONLY, <UsersPage />)} />
        <Route path="/settings" element={guarded(ADMIN_ONLY, <SettingsPage />)} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

function NotFound() {
  return (
    <div className="card mx-auto mt-10 max-w-lg px-6 py-10 text-center">
      <h1 className="text-lg font-semibold text-navy-900">There is no page at this address</h1>
      <Link to="/" className="btn-primary mt-5">
        Back to the dashboard
      </Link>
    </div>
  );
}
