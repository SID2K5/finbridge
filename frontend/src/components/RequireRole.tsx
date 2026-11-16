import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { roleName } from '../lib/format';
import type { Role } from '../types/api';
import { Loading } from './PageState';

/** Sends a visitor without a session to the login page and remembers where they wanted to go. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, restoring } = useAuth();
  const location = useLocation();
  if (restoring) {
    return <Loading what="Restoring your session" />;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}

/**
 * Route guard by role. It keeps people out of pages whose data the backend would refuse
 * them anyway; the backend's own check is what actually protects the data.
 */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { hasRole } = useAuth();
  if (!hasRole(...roles)) {
    return (
      <div className="card mx-auto mt-10 max-w-lg px-6 py-10 text-center">
        <h1 className="text-lg font-semibold text-navy-900">You do not have access to this page</h1>
        <p className="mt-2 text-sm text-slate-600">It is available to: {roles.map(roleName).join(', ')}.</p>
        <Link to="/" className="btn-primary mt-5">
          Back to the dashboard
        </Link>
      </div>
    );
  }
  return <>{children}</>;
}
