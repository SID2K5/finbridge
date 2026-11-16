package com.finbridge.integration.odoo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A posted vendor bill (account.move with move_type = in_invoice) as FinBridge needs it.
 * Nothing is validated here: a missing partner, date or currency arrives as null and the
 * synchronization decides what to do with it.
 */
public record OdooVendorBill(
        long id,
        String number,
        String vendorReference,
        Long partnerId,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal untaxedAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        String paymentState,
        String origin,
        Instant writeDate) {
}
