package com.finbridge.invoice;

import com.finbridge.supplier.Supplier;
import com.finbridge.supplier.SupplierSyncService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of the invoice module, used only by synchronization. It owns the rules
 * for accepting a bill:
 *
 *   - An invalid bill (no number, supplier, date or currency, or a negative or missing
 *     total) is never stored; the caller records the reason.
 *   - A bill is identified by its Odoo move id, so synchronizing twice updates the same
 *     row instead of creating a second one.
 *   - A bill FinBridge is paying or has paid is never overwritten when its amount or
 *     currency changes in Odoo. It is marked STALE for a person to look at.
 */
@Service
public class InvoiceSyncService {

    public enum Action { CREATED, UPDATED, UNCHANGED, REJECTED_INVALID }

    public record Outcome(Action action, Long invoiceId, String message) {
    }

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    private final InvoiceRepository invoiceRepository;
    private final SupplierSyncService supplierSyncService;

    public InvoiceSyncService(InvoiceRepository invoiceRepository, SupplierSyncService supplierSyncService) {
        this.invoiceRepository = invoiceRepository;
        this.supplierSyncService = supplierSyncService;
    }

    /** Null when the bill is acceptable, otherwise the reason it is rejected. */
    public String rejectionReason(InvoiceSyncData data) {
        if (data.invoiceNumber() == null) {
            return "Bill has no number";
        }
        if (data.invoiceNumber().length() > 64) {
            return "Bill number is longer than 64 characters";
        }
        if (data.odooPartnerId() == null) {
            return "Bill has no supplier (partner)";
        }
        if (data.invoiceDate() == null) {
            return "Bill has no invoice date";
        }
        if (data.currency() == null) {
            return "Bill has no currency";
        }
        if (!CURRENCY.matcher(data.currency()).matches()) {
            return "Currency '" + data.currency() + "' is not a three-letter ISO code";
        }
        if (data.totalAmount() == null) {
            return "Bill has no total amount";
        }
        if (data.totalAmount().signum() < 0) {
            return "Total amount is negative (" + data.totalAmount().toPlainString() + ")";
        }
        for (BigDecimal amount : new BigDecimal[] {data.totalAmount(), data.untaxedAmount(), data.taxAmount()}) {
            if (amount != null && amount.abs().compareTo(MAX_AMOUNT) > 0) {
                return "An amount is too large to store";
            }
        }
        if (data.odooWriteDate() == null) {
            return "Bill has no write date";
        }
        return null;
    }

    @Transactional
    public Outcome apply(InvoiceSyncData data, Instant now) {
        String reason = rejectionReason(data);
        if (reason != null) {
            return new Outcome(Action.REJECTED_INVALID, null, reason);
        }
        Optional<Supplier> supplier = supplierSyncService.findByOdooPartnerId(data.odooPartnerId());
        if (supplier.isEmpty()) {
            return new Outcome(Action.REJECTED_INVALID, null,
                    "Supplier (Odoo partner " + data.odooPartnerId() + ") could not be loaded from Odoo");
        }

        BigDecimal total = money(data.totalAmount());
        BigDecimal untaxed = data.untaxedAmount() != null ? money(data.untaxedAmount()) : total;
        BigDecimal tax = data.taxAmount() != null ? money(data.taxAmount()) : BigDecimal.ZERO.setScale(4);

        Optional<Invoice> existing = invoiceRepository.findByOdooMoveId(data.odooMoveId());
        if (existing.isEmpty()) {
            Invoice invoice = new Invoice(data.odooMoveId(), data.invoiceNumber(), supplier.get(),
                    data.invoiceDate(), untaxed, tax, total, data.currency(), data.odooWriteDate(), now);
            copyDescriptiveFields(data, invoice);
            return new Outcome(Action.CREATED, invoiceRepository.save(invoice).getId(), null);
        }

        Invoice invoice = existing.get();
        if (!data.odooWriteDate().isAfter(invoice.getOdooWriteDate())) {
            return new Outcome(Action.UNCHANGED, invoice.getId(), null);
        }

        boolean paymentStarted = invoice.getInvoiceStatus() == InvoiceStatus.PAYMENT_IN_PROGRESS
                || invoice.getInvoiceStatus() == InvoiceStatus.PAID;
        boolean moneyChanged = invoice.getTotalAmount().compareTo(total) != 0
                || !invoice.getCurrency().equals(data.currency());
        invoice.setOdooWriteDate(data.odooWriteDate());
        invoice.setLastSyncedAt(now);
        invoice.setOdooPaymentState(limit(data.odooPaymentState(), 24));

        if (paymentStarted && moneyChanged) {
            invoice.setSyncStatus(SyncStatus.STALE);
            return new Outcome(Action.UPDATED, invoice.getId(),
                    "Amount changed in Odoo from " + invoice.getTotalAmount().toPlainString() + " "
                            + invoice.getCurrency() + " to " + total.toPlainString() + " " + data.currency()
                            + " after payment started. FinBridge kept its own values and marked the invoice STALE");
        }

        invoice.setInvoiceNumber(data.invoiceNumber());
        invoice.setSupplier(supplier.get());
        invoice.setInvoiceDate(data.invoiceDate());
        invoice.setAmounts(untaxed, tax, total);
        invoice.setCurrency(data.currency());
        copyDescriptiveFields(data, invoice);
        invoice.setSyncStatus(SyncStatus.UPDATED);
        return new Outcome(Action.UPDATED, invoice.getId(), null);
    }

    private static void copyDescriptiveFields(InvoiceSyncData data, Invoice invoice) {
        invoice.setVendorReference(limit(data.vendorReference(), 128));
        invoice.setPoReference(limit(data.poReference(), 64));
        invoice.setDueDate(data.dueDate());
        invoice.setOdooPaymentState(limit(data.odooPaymentState(), 24));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
