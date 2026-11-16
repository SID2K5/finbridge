package com.finbridge.invoice;

/** Where an invoice stands in FinBridge's own payment process. */
public enum InvoiceStatus {
    OPEN,
    PAYMENT_IN_PROGRESS,
    PAID,
    PAYMENT_FAILED
}
