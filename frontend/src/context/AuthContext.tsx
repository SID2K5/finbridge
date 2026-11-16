import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { hasAnyRole } from '../lib/roles';
import { setAccessToken, setUnauthorizedHandler } from '../services/api';
import { authService } from '../services/auth';
import type { Role, User } from '../types/api';

/**
 * The signed-in session: the access token, the current user and a role check.
 *
 * The token is kept in React state and mirrored to sessionStorage, so a page reload keeps
 * the session and closing the tab ends it. sessionStorage can be read by script running on
 * this page; the defence is a Content-Security-Policy that allows no foreign or inline
 * script (see vite.config.ts) and never rendering HTML from data.
 */

const STORAGE_KEY = 'finbridge.session';

interface Session {
  token: string;
  user: User;
}

interface AuthState {
  user: User | null;
  /** True while a stored session is being checked against the backend at startup. */
  restoring: boolean;
  /** Set when the backend ended the session (expired token, disabled user). */
  sessionEnded: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

function readStoredSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    const stored = readStoredSession();
    // Hand the token to the HTTP client before the first request of the first render.
    setAccessToken(stored?.token ?? null);
    return stored;
  });
  const [restoring, setRestoring] = useState<boolean>(() => readStoredSession() !== null);
  const [sessionEnded, setSessionEnded] = useState(false);

  const store = useCallback((next: Session | null) => {
    setAccessToken(next?.token ?? null);
    setSession(next);
    if (next) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  // Any 401 from the backend ends the session, wherever in the app it happens.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      store(null);
      setSessionEnded(true);
    });
  }, [store]);

  // A stored session may be stale (roles changed, user disabled, token expired): ask once.
  useEffect(() => {
    const stored = readStoredSession();
    if (!stored) {
      return;
    }
    let cancelled = false;
    authService
      .me()
      .then((user) => {
        if (!cancelled) {
          store({ token: stored.token, user });
        }
      })
      .catch(() => undefined) // a 401 has already signed us out through the handler above
      .finally(() => {
        if (!cancelled) {
          setRestoring(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [store]);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await authService.login(email, password);
      setSessionEnded(false);
      store({ token: response.accessToken, user: response.user });
    },
    [store],
  );

  const logout = useCallback(() => {
    setSessionEnded(false);
    store(null);
  }, [store]);

  const value = useMemo<AuthState>(
    () => ({
      user: session?.user ?? null,
      restoring,
      sessionEnded,
      login,
      logout,
      hasRole: (...roles: Role[]) => hasAnyRole(session?.user.roles, roles),
    }),
    [session, restoring, sessionEnded, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
