import { describe, expect, it } from 'vitest';
import { date, label, money, moneyList, newIdempotencyKey, roleName } from './format';
import { hasAnyRole, visibleNavItems } from './roles';

describe('format', () => {
  it('shows money with two decimals and its currency', () => {
    expect(money(59000, 'INR')).toBe('59,000.00 INR');
    expect(money(7499.99, 'INR')).toBe('7,499.99 INR');
  });

  it('shows a missing amount as a dash, never as zero', () => {
    expect(money(undefined, 'INR')).toBe('–');
    expect(money(null)).toBe('–');
    expect(money(0, 'INR')).toBe('0.00 INR');
  });

  it('never adds amounts of different currencies together', () => {
    expect(moneyList([{ currency: 'INR', amount: 100 }, { currency: 'USD', amount: 5 }])).toBe('100.00 INR · 5.00 USD');
    expect(moneyList([])).toBeNull();
    expect(moneyList(undefined)).toBeNull();
  });

  it('formats calendar dates without shifting them across time zones', () => {
    expect(date('2026-11-05')).toBe('05 Nov 2026');
    expect(date(undefined)).toBe('–');
  });

  it('turns status codes into readable labels', () => {
    expect(label('PAYMENT_IN_PROGRESS')).toBe('Payment in progress');
    expect(label('TIMEOUT_UNKNOWN')).toBe('Timeout unknown');
    expect(roleName('INTEGRATION_OPERATOR')).toBe('Integration Operator');
  });

  it('creates a different UUID for every payment intention', () => {
    const first = newIdempotencyKey();
    const second = newIdempotencyKey();
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(second).not.toBe(first);
  });
});

describe('roles', () => {
  const paths = (roles: Parameters<typeof visibleNavItems>[0]) => visibleNavItems(roles).map((item) => item.to);

  it('shows an administrator every page', () => {
    expect(paths(['ADMIN'])).toEqual(['/', '/invoices', '/payments', '/reconciliation', '/integrations', '/integrations/logs', '/admin/users', '/settings']);
  });

  it('shows a finance manager reconciliation but not integration or administration pages', () => {
    expect(paths(['FINANCE_MANAGER'])).toEqual(['/', '/invoices', '/payments', '/reconciliation']);
  });

  it('shows an integration operator monitoring and logs but not reconciliation', () => {
    expect(paths(['INTEGRATION_OPERATOR'])).toEqual(['/', '/invoices', '/payments', '/integrations', '/integrations/logs']);
  });

  it('shows nothing to someone with no role or no session', () => {
    expect(paths([])).toEqual([]);
    expect(paths(undefined)).toEqual([]);
    expect(hasAnyRole(undefined, ['ADMIN'])).toBe(false);
  });

  it('combines the pages of several roles', () => {
    expect(paths(['FINANCE_MANAGER', 'INTEGRATION_OPERATOR'])).toEqual(['/', '/invoices', '/payments', '/reconciliation', '/integrations', '/integrations/logs']);
  });
});
