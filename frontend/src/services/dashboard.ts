import type { Activity, DashboardSummary, DayCount, StatusCount } from '../types/api';
import { getData } from './api';

export const dashboardService = {
  summary: () => getData<DashboardSummary>('/dashboard/summary'),
  transactions: () => getData<StatusCount[]>('/dashboard/transactions'),
  invoiceTrend: (days: number) => getData<DayCount[]>('/dashboard/invoice-trend', { days }),
  recentActivity: () => getData<Activity[]>('/dashboard/recent-activity'),
};
