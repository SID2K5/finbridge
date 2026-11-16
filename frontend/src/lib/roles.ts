import type { Role } from '../types/api';

/**
 * Who may see or do what in the user interface. This only decides what is SHOWN: the
 * backend enforces every one of these rules again, so hiding a button is a convenience,
 * never the protection.
 */
export const ALL: Role[] = ['ADMIN', 'FINANCE_MANAGER', 'INTEGRATION_OPERATOR'];
export const FINANCE: Role[] = ['ADMIN', 'FINANCE_MANAGER'];
export const OPERATIONS: Role[] = ['ADMIN', 'INTEGRATION_OPERATOR'];
export const ADMIN_ONLY: Role[] = ['ADMIN'];

export function hasAnyRole(userRoles: readonly Role[] | undefined, allowed: readonly Role[]): boolean {
  if (!userRoles) {
    return false;
  }
  return userRoles.some((role) => allowed.includes(role));
}

export interface NavItem {
  to: string;
  label: string;
  roles: Role[];
  section: 'Finance' | 'Integration' | 'Administration';
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', roles: ALL, section: 'Finance' },
  { to: '/invoices', label: 'Invoices', roles: ALL, section: 'Finance' },
  { to: '/payments', label: 'Payments', roles: ALL, section: 'Finance' },
  { to: '/reconciliation', label: 'Reconciliation', roles: FINANCE, section: 'Finance' },
  { to: '/integrations', label: 'Monitoring', roles: OPERATIONS, section: 'Integration' },
  { to: '/integrations/logs', label: 'Integration logs', roles: OPERATIONS, section: 'Integration' },
  { to: '/admin/users', label: 'Users', roles: ADMIN_ONLY, section: 'Administration' },
  { to: '/settings', label: 'Settings', roles: ADMIN_ONLY, section: 'Administration' },
];

export function visibleNavItems(userRoles: readonly Role[] | undefined): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAnyRole(userRoles, item.roles));
}
