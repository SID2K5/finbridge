package com.finbridge.integration.odoo;

import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogEntry.Type;
import com.finbridge.logging.IntegrationLogService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * An in-memory stand-in for Odoo. It exists ONLY under the "mock-odoo" Spring profile, for
 * automated tests and for working on the rest of the system while Odoo is switched off.
 *
 * It can never pass for the real integration: the application prints a warning banner at
 * startup, GET /api/integrations/status reports mode MOCK, every record name starts with
 * [MOCK], and every integration log it writes has an operation starting with "MOCK ".
 */
@Component
@Profile("mock-odoo")
public class OdooMockAdapter implements OdooGateway {

    private static final Logger log = LoggerFactory.getLogger(OdooMockAdapter.class);

    private static final Comparator<OdooPartner> PARTNER_ORDER =
            Comparator.comparing(OdooPartner::writeDate).thenComparing(OdooPartner::id);
    private static final Comparator<OdooVendorBill> BILL_ORDER =
            Comparator.comparing(OdooVendorBill::writeDate).thenComparing(OdooVendorBill::id);

    private final IntegrationLogService integrationLog;
    private final List<OdooPartner> partners = new CopyOnWriteArrayList<>();
    private final List<OdooPartner> contacts = new CopyOnWriteArrayList<>();
    private final List<OdooVendorBill> bills = new CopyOnWriteArrayList<>();
    private volatile OdooException failBillsWith;
    private volatile java.util.concurrent.CountDownLatch holdBills;

    public OdooMockAdapter(IntegrationLogService integrationLog) {
        this.integrationLog = integrationLog;
        reset();
    }

    @PostConstruct
    void warn() {
        log.warn("""

                ************************************************************************
                *  MOCK ODOO ADAPTER ACTIVE (Spring profile "mock-odoo").              *
                *  No request is sent to Odoo. Synchronized records are invented and   *
                *  labelled [MOCK]. Do not present this run as a real Odoo integration.*
                ************************************************************************
                """);
    }

    /** Restores the built-in data set: three suppliers, three valid bills and one invalid bill. */
    public final void reset() {
        Instant written = Instant.parse("2026-11-01T08:00:00Z");
        partners.clear();
        contacts.clear();
        bills.clear();
        failBillsWith = null;
        releaseBills();
        partners.add(new OdooPartner(910001, "[MOCK] Northwind Components", "MOCK-TAX-1", "ap@northwind.example",
                "+91 00000 10001", "IN", true, written));
        partners.add(new OdooPartner(910002, "[MOCK] Meridian Freight", "MOCK-TAX-2", "billing@meridian.example",
                "+91 00000 10002", "IN", true, written.plusSeconds(60)));
        partners.add(new OdooPartner(910003, "[MOCK] Harbour Stationery", null, null, null, "IN", true,
                written.plusSeconds(120)));
        bills.add(bill(910001, "MOCK/BILL/2026/0001", "NW-1001", 910001L, LocalDate.of(2026, 10, 28),
                "25000.00", "4500.00", written.plusSeconds(300)));
        bills.add(bill(910002, "MOCK/BILL/2026/0002", "MF-778", 910002L, LocalDate.of(2026, 10, 29),
                "8000.00", "1440.00", written.plusSeconds(360)));
        bills.add(bill(910003, "MOCK/BILL/2026/0003", "HS-42", 910003L, LocalDate.of(2026, 10, 30),
                "1200.50", "216.09", written.plusSeconds(420)));
        // Invalid on purpose: no invoice date. Synchronization must reject it with a reason.
        bills.add(new OdooVendorBill(910004, "MOCK/BILL/2026/0004", "NW-1002", 910001L, null, null,
                new BigDecimal("500.00"), new BigDecimal("90.00"), new BigDecimal("590.00"), "INR",
                "not_paid", null, written.plusSeconds(480)));
    }

    private static OdooVendorBill bill(long id, String number, String ref, Long partnerId, LocalDate date,
                                       String untaxed, String tax, Instant writeDate) {
        BigDecimal untaxedAmount = new BigDecimal(untaxed);
        BigDecimal taxAmount = new BigDecimal(tax);
        return new OdooVendorBill(id, number, ref, partnerId, date, date.plusDays(30), untaxedAmount, taxAmount,
                untaxedAmount.add(taxAmount), "INR", "not_paid", "MOCK-P" + id, writeDate);
    }

    /** Test hooks: replace or add records, or make the bill call fail. */
    public List<OdooPartner> partners() {
        return partners;
    }

    /** Partners without a supplier rank: never returned by the supplier pass, only when asked for by id. */
    public List<OdooPartner> contacts() {
        return contacts;
    }

    public List<OdooVendorBill> bills() {
        return bills;
    }

    /** Makes the next bill call wait until releaseBills() (at most 20 seconds). */
    public void holdBills() {
        this.holdBills = new java.util.concurrent.CountDownLatch(1);
    }

    public final void releaseBills() {
        java.util.concurrent.CountDownLatch latch = holdBills;
        if (latch != null) {
            latch.countDown();
        }
        holdBills = null;
    }

    public void failBillsWith(OdooException failure) {
        this.failBillsWith = failure;
    }

    @Override
    public Mode mode() {
        return Mode.MOCK;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public void ping() {
        record("MOCK ping", null, "{}");
    }

    @Override
    public List<OdooPartner> fetchSuppliers(Instant since, int offset, int limit, String reference) {
        List<OdooPartner> page = partners.stream()
                .filter(partner -> since == null || !partner.writeDate().isBefore(since))
                .sorted(PARTNER_ORDER).skip(offset).limit(limit).toList();
        record("MOCK res.partner/search_read", reference, "{\"records\":" + page.size() + "}");
        return page;
    }

    @Override
    public List<OdooPartner> fetchPartnersByIds(Collection<Long> ids, String reference) {
        List<OdooPartner> found = java.util.stream.Stream.concat(partners.stream(), contacts.stream())
                .filter(partner -> ids.contains(partner.id())).toList();
        record("MOCK res.partner/search_read", reference, "{\"records\":" + found.size() + "}");
        return found;
    }

    @Override
    public List<OdooVendorBill> fetchVendorBills(Instant since, int offset, int limit, String reference) {
        java.util.concurrent.CountDownLatch latch = holdBills;
        if (latch != null) {
            try {
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        OdooException failure = failBillsWith;
        if (failure != null) {
            integrationLog.record(IntegrationLogEntry.failure(Type.ODOO, "MOCK account.move/search_read", reference,
                    IntegrationLogEntry.Outcome.FAILURE, failure.getHttpStatus(), 0, failure.getCode(),
                    failure.getMessage(), null, null, failure.getKind().retryable()));
            throw failure;
        }
        List<OdooVendorBill> page = bills.stream()
                .filter(bill -> since == null || !bill.writeDate().isBefore(since))
                .sorted(BILL_ORDER).skip(offset).limit(limit).toList();
        record("MOCK account.move/search_read", reference, "{\"records\":" + page.size() + "}");
        return page;
    }

    private void record(String operation, String reference, String response) {
        integrationLog.record(IntegrationLogEntry.success(Type.ODOO, operation, reference, null, 0, null, response));
    }
}
