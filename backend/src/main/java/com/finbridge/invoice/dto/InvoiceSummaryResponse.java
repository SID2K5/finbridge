package com.finbridge.invoice.dto;

import com.finbridge.invoice.Invoice;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of the invoice table. */
public record InvoiceSummaryResponse(
        Long id,
        String invoiceNumber,
        Long supplierId,
        String supplierName,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal totalAmount,
        String currency,
        String invoiceStatus,
        String syncStatus) {

    public static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(invoice.getId(), invoice.getInvoiceNumber(),
                invoice.getSupplier().getId(), invoice.getSupplier().getName(),
                invoice.getInvoiceDate(), invoice.getDueDate(), invoice.getTotalAmount(), invoice.getCurrency(),
                invoice.getInvoiceStatus().name(), invoice.getSyncStatus().name());
    }
}
