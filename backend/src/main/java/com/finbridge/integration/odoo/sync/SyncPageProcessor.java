package com.finbridge.integration.odoo.sync;

import com.finbridge.integration.odoo.OdooPartner;
import com.finbridge.integration.odoo.OdooVendorBill;
import com.finbridge.integration.odoo.history.SynchronizationHistory;
import com.finbridge.integration.odoo.history.SynchronizationHistoryRepository;
import com.finbridge.invoice.InvoiceSyncData;
import com.finbridge.invoice.InvoiceSyncService;
import com.finbridge.supplier.SupplierSyncData;
import com.finbridge.supplier.SupplierSyncService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves ONE page of Odoo records in ONE database transaction of its own. If page 7 fails,
 * pages 1 to 6 are already committed and stay. No network call happens inside these
 * transactions.
 */
@Component
public class SyncPageProcessor {

    public record BillPageResult(int created, int updated, int unchanged, int rejected) {
    }

    private final SupplierSyncService supplierSyncService;
    private final InvoiceSyncService invoiceSyncService;
    private final SynchronizationHistoryRepository historyRepository;

    public SyncPageProcessor(SupplierSyncService supplierSyncService, InvoiceSyncService invoiceSyncService,
                             SynchronizationHistoryRepository historyRepository) {
        this.supplierSyncService = supplierSyncService;
        this.invoiceSyncService = invoiceSyncService;
        this.historyRepository = historyRepository;
    }

    /** Returns how many suppliers were created or updated. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int saveSuppliers(long runId, List<OdooPartner> partners) {
        int upserted = 0;
        for (OdooPartner partner : partners) {
            SupplierSyncData data = new SupplierSyncData(partner.id(), partner.name(), partner.vat(),
                    partner.email(), partner.phone(), partner.countryCode(), partner.active(), partner.writeDate());
            String reason = supplierSyncService.rejectionReason(data);
            if (reason != null) {
                history(runId, "SUPPLIER", partner.id(), null, "REJECTED_INVALID", reason);
                continue;
            }
            SupplierSyncService.Result result = supplierSyncService.upsert(data);
            if (result != SupplierSyncService.Result.UNCHANGED) {
                upserted++;
                history(runId, "SUPPLIER", partner.id(), null, result.name(), partner.name());
            }
        }
        return upserted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillPageResult saveBills(long runId, List<OdooVendorBill> bills, Instant now) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int rejected = 0;
        for (OdooVendorBill bill : bills) {
            InvoiceSyncData data = new InvoiceSyncData(bill.id(), bill.number(), bill.vendorReference(),
                    bill.origin(), bill.partnerId(), bill.invoiceDate(), bill.dueDate(), bill.untaxedAmount(),
                    bill.taxAmount(), bill.totalAmount(), bill.currency(), bill.paymentState(), bill.writeDate());
            InvoiceSyncService.Outcome outcome = invoiceSyncService.apply(data, now);
            switch (outcome.action()) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case REJECTED_INVALID -> rejected++;
            }
            history(runId, "INVOICE", bill.id(), outcome.invoiceId(), outcome.action().name(), outcome.message());
        }
        return new BillPageResult(created, updated, unchanged, rejected);
    }

    private void history(long runId, String entityType, long odooRecordId, Long invoiceId, String action,
                         String message) {
        String text = message != null && message.length() > 500 ? message.substring(0, 500) : message;
        historyRepository.save(new SynchronizationHistory(runId, entityType, odooRecordId, invoiceId, action, text));
    }
}
