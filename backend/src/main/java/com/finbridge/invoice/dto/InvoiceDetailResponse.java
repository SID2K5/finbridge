package com.finbridge.invoice.dto;

import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoicePaymentSummary;
import com.finbridge.supplier.SupplierResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record InvoiceDetailResponse(
        Long id,
        Long odooMoveId,
        String invoiceNumber,
        String vendorReference,
        String poReference,
        SupplierResponse supplier,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal untaxedAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        String invoiceStatus,
        String odooPaymentState,
        String syncStatus,
        Instant odooWriteDate,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt,
        List<InvoicePaymentSummary> payments) {

    public static InvoiceDetailResponse from(Invoice invoice, List<InvoicePaymentSummary> payments) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getOdooMoveId(), invoice.getInvoiceNumber(),
                invoice.getVendorReference(), invoice.getPoReference(), SupplierResponse.from(invoice.getSupplier()),
                invoice.getInvoiceDate(), invoice.getDueDate(), invoice.getUntaxedAmount(), invoice.getTaxAmount(),
                invoice.getTotalAmount(), invoice.getCurrency(), invoice.getInvoiceStatus().name(),
                invoice.getOdooPaymentState(), invoice.getSyncStatus().name(), invoice.getOdooWriteDate(),
                invoice.getLastSyncedAt(), invoice.getCreatedAt(), invoice.getUpdatedAt(), payments);
    }
}
