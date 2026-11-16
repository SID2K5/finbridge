package com.finbridge.invoice;

import java.math.BigDecimal;
import java.time.Instant;

/** A payment transaction as shown on the invoice detail page. */
public record InvoicePaymentSummary(
        Long id,
        String transactionReference,
        String status,
        BigDecimal requestedAmount,
        BigDecimal processedAmount,
        String currency,
        String failureReason,
        int attemptCount,
        Instant requestedAt,
        Instant respondedAt) {
}
