import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { InlineError } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useAsyncAction } from '../hooks/useAsyncAction';

export function LoginPage() {
  const { user, login, sessionEnded } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const action = useAsyncAction(async () => {
    await login(email.trim(), password);
    navigate(from, { replace: true });
  });

  if (user) {
    return <Navigate to={from} replace />;
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void action.run();
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-navy-900 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <span className="inline-flex h-12 w-12 items-center justify-center rounded-lg bg-white/10 text-lg font-bold text-white" aria-hidden="true">
            FB
          </span>
          <h1 className="mt-3 text-2xl font-semibold text-white">FinBridge</h1>
          <p className="mt-1 text-sm text-navy-200">Finance systems integration</p>
        </div>
        <form onSubmit={submit} className="space-y-4 rounded-lg bg-white p-6 shadow-xl">
          {sessionEnded && (
            <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900" role="status">
              Your session has ended. Please sign in again.
            </p>
          )}
          <label className="block">
            <span className="label">Email</span>
            <input
              className="input"
              type="email"
              autoComplete="username"
              required
              autoFocus
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label className="block">
            <span className="label">Password</span>
            <input
              className="input"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <InlineError error={action.error} />
          <button type="submit" className="btn-primary w-full" disabled={action.running}>
            {action.running ? 'Signing in…' : 'Sign in'}
          </button>
          <p className="text-center text-xs text-slate-500">Accounts are created by an administrator.</p>
        </form>
      </div>
    </div>
  );
}
