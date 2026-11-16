package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.integration.odoo.OdooException;
import com.finbridge.integration.odoo.OdooMockAdapter;
import com.finbridge.integration.odoo.OdooPartner;
import com.finbridge.integration.odoo.OdooVendorBill;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.invoice.SyncStatus;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole synchronization path against a real MySQL: HTTP request, security, background
 * run, per-page transactions, upserts, history and integration logs. Only Odoo itself is
 * replaced, by the mock adapter (profile "mock-odoo"); the JSON-2 wire format is covered by
 * OdooJson2AdapterTest and, against a live Odoo, by OdooLiveIT.
 *
 * The test profile sets the page size to 2, so every scenario with three or more records
 * crosses a page boundary.
 */
@ActiveProfiles({"test", "mock-odoo"})
class OdooSyncIT extends AbstractIntegrationTest {

    @Autowired
    private OdooMockAdapter odoo;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String operator;
    private long base;
    private Instant written;

    @BeforeEach
    void emptyOdoo() {
        odoo.reset();
        odoo.partners().clear();
        odoo.bills().clear();   // reset() already emptied contacts and cleared any failure
        operator = bearer(createUser(RoleName.INTEGRATION_OPERATOR));
        // Ids and write dates unique to this test: the database is shared by all tests.
        base = 5_000_000L + nextNumber() * 100;
        written = Instant.parse("2026-11-01T08:00:00Z").plusSeconds(nextNumber() * 3600);
    }

    private OdooPartner partner(long id, Instant writeDate) {
        return new OdooPartner(id, "Supplier " + id, "TAX-" + id, "ap" + id + "@supplier.example", null, "IN",
                true, writeDate);
    }

    private OdooVendorBill bill(long id, long partnerId, String total, Instant writeDate) {
        BigDecimal amount = new BigDecimal(total);
        return new OdooVendorBill(id, "BILL/" + id, "REF-" + id, partnerId, LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 11, 27), amount, BigDecimal.ZERO, amount, "INR", "not_paid", "P" + id, writeDate);
    }

    /** Three suppliers and three valid bills, written one minute apart. */
    private void threeSuppliersWithOneBillEach() {
        for (int i = 1; i <= 3; i++) {
            odoo.partners().add(partner(base + i, written.plusSeconds(i * 60L)));
            odoo.bills().add(bill(base + 10 + i, base + i, "1000.50", written.plusSeconds(600 + i * 60L)));
        }
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long startSync(boolean fullResync) throws Exception {
        JsonNode run = data(mockMvc.perform(post("/api/integrations/odoo/sync").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullResync\":" + fullResync + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RUNNING")));
        return run.get("id").asLong();
    }

    private JsonNode awaitRun(long runId) {
        List<JsonNode> holder = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            JsonNode detail = data(mockMvc.perform(get("/api/integrations/odoo/sync-runs/" + runId)
                    .header("Authorization", operator)).andExpect(status().isOk()));
            holder.clear();
            holder.add(detail);
            return !"RUNNING".equals(detail.get("run").get("status").asText());
        });
        return holder.get(0);
    }

    private JsonNode sync(boolean fullResync) throws Exception {
        return awaitRun(startSync(fullResync));
    }

    private Invoice invoice(long odooMoveId) {
        return invoiceRepository.findByOdooMoveId(odooMoveId).orElseThrow();
    }

    @Test
    void firstRunCreatesSuppliersAndInvoicesAcrossPages() throws Exception {
        threeSuppliersWithOneBillEach();

        JsonNode run = sync(true).get("run");

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("triggerType").asText()).isEqualTo("MANUAL");
        assertThat(run.get("triggeredBy").asLong()).isPositive();
        assertThat(run.get("suppliersUpserted").asInt()).isEqualTo(3);
        assertThat(run.get("invoicesCreated").asInt()).isEqualTo(3);
        assertThat(run.get("invoicesRejected").asInt()).isZero();
        assertThat(run.get("cursorFrom")).isNull();
        assertThat(Instant.parse(run.get("cursorTo").asText())).isEqualTo(written.plusSeconds(780));
        assertThat(run.get("finishedAt")).isNotNull();

        Invoice stored = invoice(base + 11);
        assertThat(stored.getInvoiceNumber()).isEqualTo("BILL/" + (base + 11));
        assertThat(stored.getTotalAmount()).isEqualByComparingTo("1000.50");
        assertThat(stored.getCurrency()).isEqualTo("INR");
        assertThat(stored.getPoReference()).isEqualTo("P" + (base + 11));
        assertThat(stored.getInvoiceStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(stored.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(supplierRepository.findByOdooPartnerId(base + 1)).isPresent();

        // The invoice's own history endpoint (built in Phase 2) now has a real entry.
        mockMvc.perform(get("/api/invoices/" + stored.getId() + "/history").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("CREATED"))
                .andExpect(jsonPath("$.data[0].odooRecordId").value(base + 11));
    }

    @Test
    void runningAgainCreatesNoDuplicatesAndReportsUnchanged() throws Exception {
        threeSuppliersWithOneBillEach();
        sync(true);
        long invoicesBefore = invoiceRepository.count();
        long suppliersBefore = supplierRepository.count();

        JsonNode again = sync(true).get("run");

        assertThat(again.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(again.get("invoicesCreated").asInt()).isZero();
        assertThat(again.get("invoicesUpdated").asInt()).isZero();
        assertThat(again.get("invoicesUnchanged").asInt()).isEqualTo(3);
        assertThat(again.get("suppliersUpserted").asInt()).isZero();
        assertThat(invoiceRepository.count()).isEqualTo(invoicesBefore);
        assertThat(supplierRepository.count()).isEqualTo(suppliersBefore);
    }

    @Test
    void anIncrementalRunReadsOnlyWhatChangedSinceTheCursor() throws Exception {
        // Far in the future, so this test's cursor is the newest whatever ran before it.
        written = Instant.parse("2030-01-01T00:00:00Z").plusSeconds(nextNumber() * 3600);
        threeSuppliersWithOneBillEach();
        JsonNode first = sync(true).get("run");
        Instant cursor = Instant.parse(first.get("cursorTo").asText());

        // One bill is changed in Odoo and one new bill is posted, both after the cursor.
        odoo.bills().removeIf(bill -> bill.id() == base + 12);
        odoo.bills().add(bill(base + 12, base + 2, "2222.00", cursor.plusSeconds(60)));
        odoo.bills().add(bill(base + 14, base + 3, "40.00", cursor.plusSeconds(120)));

        JsonNode second = sync(false).get("run");

        assertThat(Instant.parse(second.get("cursorFrom").asText())).isEqualTo(cursor);
        assertThat(second.get("invoicesCreated").asInt()).isEqualTo(1);
        assertThat(second.get("invoicesUpdated").asInt()).isEqualTo(1);
        // "since" is inclusive: the bill that set the cursor is read again and found unchanged.
        assertThat(second.get("invoicesUnchanged").asInt()).isEqualTo(1);
        assertThat(Instant.parse(second.get("cursorTo").asText())).isEqualTo(cursor.plusSeconds(120));
        assertThat(invoice(base + 12).getTotalAmount()).isEqualByComparingTo("2222.00");
        assertThat(invoice(base + 12).getSyncStatus()).isEqualTo(SyncStatus.UPDATED);
    }

    @Test
    void anInvalidBillIsRejectedWithAReasonAndTheRestIsKept() throws Exception {
        threeSuppliersWithOneBillEach();
        // One bill without an invoice date and one with a negative total: neither may be stored.
        odoo.bills().add(new OdooVendorBill(base + 20, "BILL/" + (base + 20), null, base + 1, null, null,
                new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"), "INR", "not_paid", null,
                written.plusSeconds(700)));
        odoo.bills().add(new OdooVendorBill(base + 21, "BILL/" + (base + 21), null, base + 1,
                LocalDate.of(2026, 10, 1), null, new BigDecimal("-5"), BigDecimal.ZERO, new BigDecimal("-5"), "INR",
                "not_paid", null, written.plusSeconds(710)));

        JsonNode detail = sync(true);

        JsonNode run = detail.get("run");
        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("invoicesCreated").asInt()).isEqualTo(3);
        assertThat(run.get("invoicesRejected").asInt()).isEqualTo(2);
        assertThat(run.get("errorMessage").asText()).contains("2 bill(s) were rejected");
        assertThat(run.get("cursorTo")).isNotNull();   // PARTIAL still advances over what was processed
        assertThat(invoiceRepository.findByOdooMoveId(base + 20)).isEmpty();
        assertThat(invoiceRepository.findByOdooMoveId(base + 21)).isEmpty();

        List<String> reasons = jdbcTemplate.queryForList(
                "SELECT message FROM synchronization_history WHERE sync_run_id = ? AND action = 'REJECTED_INVALID' "
                        + "ORDER BY odoo_record_id", String.class, run.get("id").asLong());
        assertThat(reasons).hasSize(2);
        assertThat(reasons.get(0)).contains("no invoice date");
        assertThat(reasons.get(1)).contains("negative");
        assertThat(detail.get("history")).isNotEmpty();
    }

    @Test
    void aBillWhosePartnerIsNotASupplierStillGetsItsSupplier() throws Exception {
        // The partner has no supplier rank in Odoo, so the supplier pass never returns it;
        // the run must fetch it by id when it meets the bill.
        long contactId = base + 5;
        odoo.contacts().add(partner(contactId, written));
        odoo.bills().add(bill(base + 15, contactId, "75.00", written.plusSeconds(60)));

        JsonNode run = sync(true).get("run");

        assertThat(run.get("suppliersUpserted").asInt()).isEqualTo(1);
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(invoice(base + 15).getSupplier().getId())
                .isEqualTo(supplierRepository.findByOdooPartnerId(contactId).orElseThrow().getId());
    }

    @Test
    void aPaidInvoiceIsNotOverwrittenWhenOdooChangesItsAmount() throws Exception {
        threeSuppliersWithOneBillEach();
        sync(true);
        Invoice paid = invoice(base + 11);
        paid.setInvoiceStatus(InvoiceStatus.PAID);
        invoiceRepository.save(paid);

        odoo.bills().removeIf(bill -> bill.id() == base + 11);
        odoo.bills().add(bill(base + 11, base + 1, "9999.00", written.plusSeconds(5000)));
        JsonNode run = sync(true).get("run");

        assertThat(run.get("invoicesUpdated").asInt()).isEqualTo(1);
        Invoice after = invoice(base + 11);
        assertThat(after.getTotalAmount()).isEqualByComparingTo("1000.50");
        assertThat(after.getSyncStatus()).isEqualTo(SyncStatus.STALE);
        assertThat(after.getInvoiceStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void whenOdooFailsBeforeAnythingIsSavedTheRunFailsAndTheCursorDoesNotMove() throws Exception {
        odoo.failBillsWith(new OdooException(OdooException.Kind.AUTH, 401,
                "HTTP 401 werkzeug.exceptions.Unauthorized: Invalid apikey"));

        JsonNode run = sync(true).get("run");

        assertThat(run.get("status").asText()).isEqualTo("FAILED");
        assertThat(run.get("cursorTo")).isNull();
        assertThat(run.get("errorMessage").asText()).contains("ODOO_AUTH", "Invalid apikey");

        mockMvc.perform(get("/api/integrations/status").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.odoo.mode").value("MOCK"))
                .andExpect(jsonPath("$.data.odoo.state").value("DOWN"))
                .andExpect(jsonPath("$.data.odoo.lastError.code").value("ODOO_AUTH"))
                .andExpect(jsonPath("$.data.odoo.lastSync.status").value("FAILED"))
                .andExpect(jsonPath("$.data.soap.state").exists())
                .andExpect(jsonPath("$.data.rest.state").exists());
    }

    @Test
    void whenBillsFailAfterSuppliersWereSavedTheRunIsPartialAndKeepsTheSuppliers() throws Exception {
        threeSuppliersWithOneBillEach();
        odoo.failBillsWith(new OdooException(OdooException.Kind.TIMEOUT, null, "Odoo did not answer in time"));

        JsonNode run = sync(true).get("run");

        assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.get("suppliersUpserted").asInt()).isEqualTo(3);
        assertThat(run.get("invoicesCreated").asInt()).isZero();
        assertThat(run.get("cursorTo")).isNull();
        assertThat(supplierRepository.findByOdooPartnerId(base + 3)).isPresent();
    }

    @Test
    void everyOdooCallIsInTheIntegrationLogUnderTheRequestsCorrelationId() throws Exception {
        threeSuppliersWithOneBillEach();
        String correlationId = java.util.UUID.randomUUID().toString();
        JsonNode started = data(mockMvc.perform(post("/api/invoices/sync").header("Authorization", operator)
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isAccepted()));
        long runId = started.get("id").asLong();
        awaitRun(runId);

        JsonNode logs = data(mockMvc.perform(get("/api/integrations/logs").header("Authorization", operator)
                        .param("type", "ODOO").param("correlationId", correlationId).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").isNumber()));

        assertThat(logs.size()).isGreaterThanOrEqualTo(4);   // 2 supplier pages + 2 bill pages at least
        assertThat(logs).allSatisfy(row -> {
            assertThat(row.get("requestReference").asText()).isEqualTo("sync-run-" + runId);
            assertThat(row.get("operation").asText()).startsWith("MOCK ");
            assertThat(row.get("outcome").asText()).isEqualTo("SUCCESS");
            assertThat(row.has("responsePayload")).isFalse();    // list rows carry no payloads
        });

        mockMvc.perform(get("/api/integrations/logs/" + logs.get(0).get("id").asLong())
                        .header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correlationId").value(correlationId))
                .andExpect(jsonPath("$.data.responsePayload").exists());
    }

    @Test
    void onlyAdminsAndOperatorsMaySynchronizeOrReadLogs() throws Exception {
        String finance = bearer(createUser(RoleName.FINANCE_MANAGER));
        String admin = bearer(createUser(RoleName.ADMIN));

        for (String path : List.of("/api/integrations/odoo/sync", "/api/invoices/sync",
                "/api/integrations/health-check")) {
            mockMvc.perform(post(path).header("Authorization", finance))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/integrations/odoo/sync-runs", "/api/integrations/logs")) {
            mockMvc.perform(get(path).header("Authorization", finance)).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", admin)).andExpect(status().isOk());
        }
        // The status summary is visible to every role.
        mockMvc.perform(get("/api/integrations/status").header("Authorization", finance))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/integrations/health-check").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.odoo.ok").value(true))
                .andExpect(jsonPath("$.data.odoo.message").value("Mock adapter: no request was sent to Odoo"));
    }

    @Test
    void anUnknownRunIsA404() throws Exception {
        mockMvc.perform(get("/api/integrations/odoo/sync-runs/999999999").header("Authorization", operator))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void aSecondRunCannotStartWhileOneIsRunning() throws Exception {
        threeSuppliersWithOneBillEach();
        odoo.holdBills();   // the run stops inside its first bill call until released
        long running = startSync(true);
        try {
            mockMvc.perform(post("/api/integrations/odoo/sync").header("Authorization", operator))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.error.message").value("A synchronization run is already in progress"));
            mockMvc.perform(post("/api/invoices/sync").header("Authorization", operator))
                    .andExpect(status().isConflict());
            // The first run is untouched by the refused attempts.
            mockMvc.perform(get("/api/integrations/odoo/sync-runs/" + running).header("Authorization", operator))
                    .andExpect(jsonPath("$.data.run.status").value("RUNNING"));
        } finally {
            odoo.releaseBills();
        }
        JsonNode run = awaitRun(running).get("run");
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("invoicesCreated").asInt()).isEqualTo(3);
        // And once it has finished, a new run may start.
        assertThat(sync(true).get("run").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void retryingARetryableOdooFailureFromTheLogStartsANewRunAndAPermanentOneIsRefused() throws Exception {
        odoo.failBillsWith(new OdooException(OdooException.Kind.TIMEOUT, null, "Odoo did not answer in time"));
        long timedOutRun = sync(true).get("run").get("id").asLong();
        long timeoutLog = jdbcTemplate.queryForObject("SELECT MAX(id) FROM integration_logs WHERE "
                + "request_reference = ? AND error_code = 'ODOO_TIMEOUT'", Long.class, "sync-run-" + timedOutRun);
        odoo.failBillsWith(new OdooException(OdooException.Kind.AUTH, 401, "Invalid apikey"));
        long refusedRun = sync(true).get("run").get("id").asLong();
        long authLog = jdbcTemplate.queryForObject("SELECT MAX(id) FROM integration_logs WHERE "
                + "request_reference = ? AND error_code = 'ODOO_AUTH'", Long.class, "sync-run-" + refusedRun);
        odoo.failBillsWith(null);

        // A wrong key fails the same way every time: retrying it is refused with the reason.
        mockMvc.perform(post("/api/integrations/logs/" + authLog + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("not retryable")));

        JsonNode retried = data(mockMvc.perform(post("/api/integrations/logs/" + timeoutLog + "/retry")
                        .header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("SYNC_STARTED")));
        JsonNode newRun = awaitRun(retried.get("syncRunId").asLong()).get("run");
        assertThat(newRun.get("id").asLong()).isGreaterThan(refusedRun);
        assertThat(newRun.get("status").asText()).isEqualTo("COMPLETED");
    }
}
