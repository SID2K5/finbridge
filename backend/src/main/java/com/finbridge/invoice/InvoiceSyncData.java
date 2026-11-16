package com.finbridge.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A vendor bill as delivered by a synchronization. Any field may be null; validation decides. */
public record InvoiceSyncData(
        long odooMoveId,
        String invoiceNumber,
        String vendorReference,
        String poReference,
        Long odooPartnerId,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal untaxedAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        String odooPaymentState,
        Instant odooWriteDate) {
}
