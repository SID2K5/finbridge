import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useQuery } from '../hooks/useQuery';
import { roleName } from '../lib/format';
import { visibleNavItems, type NavItem } from '../lib/roles';
import { integrationService } from '../services/integrations';

/** The one layout: a fixed navy sidebar, a top bar with the user and their role, and the page. */
export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const items = visibleNavItems(user?.roles);
  const sections = ['Finance', 'Integration', 'Administration'] as const;
  // Every role may read the integration status; it tells us whether Odoo is real or mocked.
  const status = useQuery(() => integrationService.status(), []);

  return (
    <div className="min-h-screen">
      <aside className="fixed inset-y-0 left-0 z-20 flex w-60 flex-col bg-navy-900 text-navy-100">
        <div className="flex h-16 items-center gap-2.5 border-b border-white/10 px-5">
          <span className="flex h-8 w-8 items-center justify-center rounded-md bg-white/10 text-sm font-bold text-white" aria-hidden="true">
            FB
          </span>
          <div>
            <p className="text-sm font-semibold leading-tight text-white">FinBridge</p>
            <p className="text-[11px] leading-tight text-navy-200">Finance integration</p>
          </div>
        </div>
        <nav className="flex-1 overflow-y-auto px-3 py-4" aria-label="Main">
          {sections.map((section) => {
            const inSection = items.filter((item) => item.section === section);
            if (inSection.length === 0) {
              return null;
            }
            return (
              <div key={section} className="mb-5">
                <p className="mb-1.5 px-2 text-[11px] font-semibold uppercase tracking-wider text-navy-400">{section}</p>
                <ul className="space-y-0.5">
                  {inSection.map((item) => (
                    <li key={item.to}>
                      <SidebarLink item={item} />
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </nav>
        <div className="border-t border-white/10 px-5 py-3 text-[11px] text-navy-400">Version 0.7.0</div>
      </aside>

      <div className="pl-60">
        <header className="sticky top-0 z-10 flex h-16 items-center justify-end gap-4 border-b border-slate-200 bg-white px-6">
          {user && (
            <div className="text-right">
              <p className="text-sm font-medium text-slate-800">{user.fullName}</p>
              <p className="text-xs text-slate-500">{user.roles.map(roleName).join(', ')}</p>
            </div>
          )}
          <button
            type="button"
            className="btn-secondary"
            onClick={() => {
              navigate('/', { replace: true });
              logout();
            }}
          >
            Sign out
          </button>
        </header>

        {status.data?.odoo.mode === 'MOCK' && (
          <div className="border-b border-amber-300 bg-amber-100 px-6 py-2.5 text-sm font-medium text-amber-900" role="alert">
            Odoo is running in MOCK mode. Synchronized records are invented and labelled [MOCK]; this is not a real
            Odoo integration.
          </div>
        )}

        <main className="mx-auto max-w-7xl px-6 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function SidebarLink({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      // "/" and "/integrations" are prefixes of other routes; they are active only on an exact match.
      end={item.to === '/' || item.to === '/integrations'}
      className={({ isActive }) =>
        `block rounded-md px-3 py-2 text-sm transition-colors ${
          isActive ? 'bg-white/15 font-semibold text-white' : 'text-navy-100 hover:bg-white/10 hover:text-white'
        }`
      }
    >
      {item.label}
    </NavLink>
  );
}
