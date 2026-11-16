import type { Payment, PaymentDetail } from '../types/api';
import { getData, getPage, send, sendWithResponse } from './api';

export interface PaymentFilter {
  status?: string;
  invoiceId?: string;
  reference?: string;
  /** yyyy-MM-dd from the date inputs; converted to whole UTC days for the API. */
  dateFrom?: string;
  dateTo?: string;
}

export const paymentService = {
  list: (filter: PaymentFilter, page: number, size: number, sort?: string) =>
    getPage<Payment>('/payments', {
      status: filter.status,
      invoiceId: filter.invoiceId,
      reference: filter.reference,
      from: filter.dateFrom ? `${filter.dateFrom}T00:00:00Z` : undefined,
      to: filter.dateTo ? `${filter.dateTo}T23:59:59Z` : undefined,
      page,
      size,
      sort,
    }),
  get: (id: number) => getData<PaymentDetail>(`/payments/${id}`),

  /**
   * Pays an invoice in full. The key identifies this payment INTENTION: the caller creates
   * it once and sends it with every attempt, so a double click or a network retry returns
   * the same transaction instead of creating a second one.
   */
  create: async (invoiceId: number, idempotencyKey: string) => {
    const { data, response } = await sendWithResponse<Payment>(
      'post',
      '/payments',
      { invoiceId },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
    return { payment: data, replayed: response.headers['idempotent-replay'] === 'true' };
  },
  statusCheck: (id: number) => send<Payment>('post', `/payments/${id}/status-check`),
  retry: (id: number) => send<Payment>('post', `/payments/${id}/retry`),
};
