package com.finbridge.integration.odoo.sync;

import com.finbridge.integration.odoo.OdooException;
import com.finbridge.integration.odoo.OdooGateway;
import com.finbridge.integration.odoo.OdooPartner;
import com.finbridge.integration.settings.SettingsService;
import com.finbridge.integration.odoo.OdooVendorBill;
import com.finbridge.integration.odoo.history.SyncRun;
import com.finbridge.logging.CorrelationIdFilter;
import com.finbridge.supplier.SupplierSyncService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one synchronization run. It is deliberately NOT transactional: it alternates
 * between a network call to Odoo and a short database transaction per page.
 *
 *   1. Suppliers changed since the cursor, page by page.
 *   2. Vendor bills changed since the cursor, page by page. Partners a bill refers to that
 *      FinBridge does not hold yet are fetched first.
 *   3. The run is closed:
 *        COMPLETED  everything was read and every bill accepted;
 *        PARTIAL    some bills were rejected, or a bill page failed after data was saved;
 *        FAILED     nothing usable (Odoo unreachable, key refused, first call failed).
 *      Only COMPLETED and PARTIAL move the cursor, and only as far as was fully processed.
 */
@Component
public class SyncRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);

    private final OdooGateway gateway;
    private final SettingsService settings;
    private final SyncPageProcessor pageProcessor;
    private final SyncRunStore runStore;
    private final SupplierSyncService supplierSyncService;
    private final Clock clock;

    public SyncRunner(OdooGateway gateway, SettingsService settings, SyncPageProcessor pageProcessor,
                      SyncRunStore runStore, SupplierSyncService supplierSyncService, Clock clock) {
        this.gateway = gateway;
        this.settings = settings;
        this.pageProcessor = pageProcessor;
        this.runStore = runStore;
        this.supplierSyncService = supplierSyncService;
        this.clock = clock;
    }

    public void run(long runId, Instant cursorFrom) {
        String reference = "sync-run-" + runId;
        int pageSize = settings.intValue(SettingsService.Setting.ODOO_PAGE_SIZE);
        log.info("Sync run {} started ({}, cursor {})", runId, gateway.mode(), cursorFrom);

        Instant lastSupplierWrite = null;
        int suppliersSaved = 0;
        try {
            for (int offset = 0; ; offset += pageSize) {
                List<OdooPartner> page = gateway.fetchSuppliers(cursorFrom, offset, pageSize, reference);
                if (page.isEmpty()) {
                    break;
                }
                int upserted = pageProcessor.saveSuppliers(runId, page);
                runStore.addSuppliers(runId, upserted);
                suppliersSaved += upserted;
                lastSupplierWrite = page.get(page.size() - 1).writeDate();
                if (page.size() < pageSize) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // Bills depend on suppliers, so a broken supplier pass ends the run.
            String status = suppliersSaved > 0 ? SyncRun.PARTIAL : SyncRun.FAILED;
            close(runId, status, null, describe(ex, "suppliers"), ex);
            return;
        }

        Instant lastBillWrite = null;
        int billsHandled = 0;
        int billsRejected = 0;
        RuntimeException billFailure = null;
        try {
            for (int offset = 0; ; offset += pageSize) {
                List<OdooVendorBill> page = gateway.fetchVendorBills(cursorFrom, offset, pageSize, reference);
                if (page.isEmpty()) {
                    break;
                }
                loadMissingPartners(runId, page, reference);
                SyncPageProcessor.BillPageResult result = pageProcessor.saveBills(runId, page, clock.instant());
                runStore.addBills(runId, result);
                billsHandled += page.size();
                billsRejected += result.rejected();
                lastBillWrite = page.get(page.size() - 1).writeDate();
                if (page.size() < pageSize) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            billFailure = ex;
        }

        if (billFailure != null) {
            if (billsHandled == 0 && suppliersSaved == 0) {
                close(runId, SyncRun.FAILED, null, describe(billFailure, "vendor bills"), billFailure);
            } else {
                // Suppliers are complete but bills are not: the cursor may only move to the
                // last bill page that was fully saved (or stay where it was).
                Instant cursorTo = lastBillWrite != null ? lastBillWrite : cursorFrom;
                close(runId, SyncRun.PARTIAL, cursorTo, describe(billFailure, "vendor bills"), billFailure);
            }
            return;
        }

        Instant cursorTo = latest(latest(cursorFrom, lastSupplierWrite), lastBillWrite);
        if (billsRejected > 0) {
            close(runId, SyncRun.PARTIAL, cursorTo,
                    billsRejected + " bill(s) were rejected as invalid; see the run's history for the reasons", null);
        } else {
            close(runId, SyncRun.COMPLETED, cursorTo, null, null);
        }
    }

    private void loadMissingPartners(long runId, List<OdooVendorBill> bills, String reference) {
        Set<Long> partnerIds = bills.stream().map(OdooVendorBill::partnerId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> missing = supplierSyncService.missingOdooPartnerIds(partnerIds);
        if (missing.isEmpty()) {
            return;
        }
        List<OdooPartner> partners = gateway.fetchPartnersByIds(missing, reference);
        runStore.addSuppliers(runId, pageProcessor.saveSuppliers(runId, partners));
    }

    private void close(long runId, String status, Instant cursorTo, String message, RuntimeException cause) {
        if (cause != null && !(cause instanceof OdooException)) {
            log.error("Sync run {} hit an unexpected error", runId, cause);
        }
        runStore.finish(runId, status, cursorTo, message);
        log.info("Sync run {} finished: {}{}", runId, status, message != null ? " (" + message + ")" : "");
    }

    private static String describe(RuntimeException ex, String stage) {
        if (ex instanceof OdooException odoo) {
            return "Reading " + stage + " from Odoo failed: " + odoo.getCode() + " - " + odoo.getMessage();
        }
        return "Saving " + stage + " failed unexpectedly. Search the application log for correlation id "
                + CorrelationIdFilter.currentId();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
