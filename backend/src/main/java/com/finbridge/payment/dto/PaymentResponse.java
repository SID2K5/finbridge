package com.finbridge.payment.dto;

import com.finbridge.payment.PaymentTransaction;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        String transactionReference,
        Long invoiceId,
        String invoiceNumber,
        Long supplierId,
        String supplierName,
        BigDecimal requestedAmount,
        String currency,
        String status,
        String externalTransactionId,
        BigDecimal processedAmount,
        String failureReason,
        int attemptCount,
        Instant requestedAt,
        Instant lastAttemptAt,
        Instant respondedAt,
        Long initiatedBy) {

    public static PaymentResponse from(PaymentTransaction tx) {
        return new PaymentResponse(tx.getId(), tx.getTransactionReference(), tx.getInvoice().getId(),
                tx.getInvoice().getInvoiceNumber(), tx.getSupplier().getId(), tx.getSupplier().getName(),
                tx.getRequestedAmount(), tx.getCurrency(), tx.getStatus().name(), tx.getExternalTransactionId(),
                tx.getProcessedAmount(), tx.getFailureReason(), tx.getAttemptCount(), tx.getRequestedAt(),
                tx.getLastAttemptAt(), tx.getRespondedAt(), tx.getInitiatedBy());
    }
}
