package com.finbridge.payment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment transaction as other modules (reconciliation) may read it: plain values, no
 * entity. supplierReference is the same "SUP-<odoo partner id>" that is sent to the payment
 * service, so both sides can be compared like with like.
 */
public record PaymentSnapshot(
        Long id,
        Long invoiceId,
        String invoiceNumber,
        String supplierReference,
        String transactionReference,
        String externalTransactionId,
        BigDecimal requestedAmount,
        BigDecimal processedAmount,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant requestedAt) {

    static PaymentSnapshot from(PaymentTransaction tx) {
        return new PaymentSnapshot(tx.getId(), tx.getInvoice().getId(), tx.getInvoice().getInvoiceNumber(),
                supplierReference(tx), tx.getTransactionReference(), tx.getExternalTransactionId(),
                tx.getRequestedAmount(), tx.getProcessedAmount(), tx.getCurrency(), tx.getStatus(),
                tx.getFailureReason(), tx.getRequestedAt());
    }

    static String supplierReference(PaymentTransaction tx) {
        return "SUP-" + tx.getSupplier().getOdooPartnerId();
    }
}
