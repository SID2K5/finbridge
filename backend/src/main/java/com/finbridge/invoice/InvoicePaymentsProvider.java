package com.finbridge.invoice;

import java.util.List;

/**
 * Lets the invoice module show an invoice's payments without depending on the payment
 * module: the interface lives here and the payment module implements it.
 */
public interface InvoicePaymentsProvider {

    List<InvoicePaymentSummary> forInvoice(Long invoiceId);
}
