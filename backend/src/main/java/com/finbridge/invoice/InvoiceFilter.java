package com.finbridge.invoice;

import java.time.LocalDate;

/** Optional search criteria for the invoice list. A null field means "do not filter on this". */
public record InvoiceFilter(
        String invoiceNumber,
        Long supplierId,
        InvoiceStatus status,
        SyncStatus syncStatus,
        LocalDate dateFrom,
        LocalDate dateTo) {
}
