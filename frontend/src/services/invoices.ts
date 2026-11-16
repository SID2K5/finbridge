import type { InvoiceDetail, InvoiceSummary, Supplier, SyncHistoryEntry } from '../types/api';
import { getData, getPage } from './api';

export interface InvoiceFilter {
  invoiceNumber?: string;
  supplierId?: string;
  status?: string;
  syncStatus?: string;
  dateFrom?: string;
  dateTo?: string;
}

export const invoiceService = {
  list: (filter: InvoiceFilter, page: number, size: number, sort?: string) =>
    getPage<InvoiceSummary>('/invoices', { ...filter, page, size, sort }),
  get: (id: number) => getData<InvoiceDetail>(`/invoices/${id}`),
  history: (id: number) => getPage<SyncHistoryEntry>(`/invoices/${id}/history`, { size: 50 }),
};

export const supplierService = {
  /** For filter dropdowns: the first 100 suppliers by name. */
  all: () => getPage<Supplier>('/suppliers', { size: 100, sort: 'name,asc' }),
};
