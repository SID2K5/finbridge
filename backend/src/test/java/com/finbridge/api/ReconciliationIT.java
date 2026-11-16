package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.supplier.Supplier;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reconciliation against a real MySQL: real payments made through the API, a scripted
 * settlement statement in place of the payment service, then the run, its records, the
 * field-by-field detail and the review workflow.
 *
 * The database is shared with the other tests and they also make payments today, so every
 * assertion looks only at this test's own invoices.
 */
@Import(PaymentIT.ScriptedGatewayConfig.class)
class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentIT.ScriptedGateway gateway;

    private String finance;
    private Supplier supplier;
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        gateway.reset();
        finance = bearer(createUser(RoleName.FINANCE_MANAGER));
        supplier = createSupplier("Reconciled Supplier " + nextNumber());
    }

    // ---------- helpers ----------

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private Invoice invoice(String total) {
        return createInvoice(supplier, "BILL/REC/" + nextNumber(), LocalDate.of(2026, 1, 15), total);
    }

    /** Pays the invoice through the API and returns the stored transaction. */
    private JsonNode pay(Invoice invoice) throws Exception {
        return data(mockMvc.perform(post("/api/payments").header("Authorization", finance)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":" + invoice.getId() + "}"))
                .andExpect(status().isCreated()));
    }

    /** Puts a line on the scripted settlement statement. */
    private void onStatement(String reference, String invoiceReference, String amount, PaymentGateway.Status status) {
        gateway.statementEntries.add(new PaymentGateway.StatementEntry(
                new PaymentGateway.Outcome("SIM-" + reference, reference, invoiceReference, new BigDecimal(amount),
                        "INR", status, Instant.now(), null),
                "SUP-" + supplier.getOdooPartnerId()));
    }

    private void onStatement(JsonNode payment, Invoice invoice, String amount, PaymentGateway.Status status) {
        onStatement(payment.get("transactionReference").asText(), invoice.getInvoiceNumber(), amount, status);
    }

    private JsonNode reconcileToday() throws Exception {
        JsonNode started = data(mockMvc.perform(post("/api/reconciliation/run").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"" + today + "\",\"periodTo\":\"" + today + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RUNNING")));
        long runId = started.get("id").asLong();
        List<JsonNode> holder = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            holder.clear();
            holder.add(data(mockMvc.perform(get("/api/reconciliation/runs/" + runId)
                    .header("Authorization", finance)).andExpect(status().isOk())));
            return !"RUNNING".equals(holder.get(0).get("status").asText());
        });
        return holder.get(0);
    }

    /** The single record of a run that belongs to the given invoice. */
    private JsonNode recordFor(JsonNode run, Invoice invoice) throws Exception {
        JsonNode records = data(mockMvc.perform(get("/api/reconciliation").header("Authorization", finance)
                        .param("runId", run.get("id").asText()).param("invoiceId", String.valueOf(invoice.getId())))
                .andExpect(status().isOk()));
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    // ---------- the rules, end to end ----------

    @Test
    void oneRunProducesAllSixStatuses() throws Exception {
        Invoice matched = invoice("1000.00");
        Invoice shortPaid = invoice("2000.00");
        Invoice pending = invoice("300.00");
        Invoice lost = invoice("400.00");
        Invoice duplicateA = invoice("500.00");
        Invoice duplicateB = invoice("500.00");
        for (Invoice copy : List.of(duplicateA, duplicateB)) {
            copy.setVendorReference("SAME-REF-" + supplier.getId());
            invoiceRepository.save(copy);
        }

        JsonNode matchedTx = pay(matched);
        JsonNode shortTx = pay(shortPaid);
        gateway.submitScript.add(reference -> new PaymentGateway.Outcome("SIM-" + reference, reference, "x",
                BigDecimal.ZERO, "INR", PaymentGateway.Status.PENDING, Instant.now(), null));
        JsonNode pendingTx = pay(pending);
        pay(lost);                                   // paid in FinBridge, absent from the statement
        JsonNode duplicateTx = pay(duplicateA);      // one of two bills with the same vendor reference

        onStatement(matchedTx, matched, "1000.0000", PaymentGateway.Status.SUCCESS);
        onStatement(shortTx, shortPaid, "1800.0000", PaymentGateway.Status.SUCCESS);
        onStatement(pendingTx, pending, "0.0000", PaymentGateway.Status.PENDING);
        onStatement(duplicateTx, duplicateA, "500.0000", PaymentGateway.Status.SUCCESS);
        String orphanReference = "EXT-" + nextNumber();
        onStatement(orphanReference, "BILL/NOT-OURS/" + nextNumber(), "77.5000", PaymentGateway.Status.SUCCESS);

        JsonNode run = reconcileToday();

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("periodFrom").asText()).isEqualTo(today.toString());
        JsonNode counts = run.get("countsByStatus");
        for (String status : List.of("MATCHED", "MISMATCH", "PENDING_REVIEW", "MISSING_EXTERNAL_RECORD",
                "MISSING_INTERNAL_RECORD", "DUPLICATE")) {
            assertThat(counts.get(status).asLong()).as(status).isPositive();
        }
        assertThat(run.get("totalRecords").asInt())
                .isEqualTo(run.get("matchedCount").asInt() + run.get("discrepancyCount").asInt());

        JsonNode matchedRecord = recordFor(run, matched);
        assertThat(matchedRecord.get("status").asText()).isEqualTo("MATCHED");
        assertThat(matchedRecord.get("reviewStatus").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(matchedRecord.has("discrepancyDescription")).isFalse();

        JsonNode mismatch = recordFor(run, shortPaid);
        assertThat(mismatch.get("status").asText()).isEqualTo("MISMATCH");
        assertThat(mismatch.get("reviewStatus").asText()).isEqualTo("OPEN");
        assertThat(mismatch.get("expectedAmount").decimalValue()).isEqualByComparingTo("2000");
        assertThat(mismatch.get("actualAmount").decimalValue()).isEqualByComparingTo("1800");
        assertThat(mismatch.get("discrepancyDescription").asText())
                .isEqualTo("amount: FinBridge requested 2000, payment service settled 1800 (difference 200)");

        assertThat(recordFor(run, pending).get("status").asText()).isEqualTo("PENDING_REVIEW");

        JsonNode missingExternal = recordFor(run, lost);
        assertThat(missingExternal.get("status").asText()).isEqualTo("MISSING_EXTERNAL_RECORD");
        assertThat(missingExternal.has("externalTransactionReference")).isFalse();

        JsonNode paidDuplicate = recordFor(run, duplicateA);
        assertThat(paidDuplicate.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(paidDuplicate.get("discrepancyDescription").asText())
                .contains(duplicateA.getInvoiceNumber(), duplicateB.getInvoiceNumber(), "SAME-REF-");
        JsonNode unpaidDuplicate = recordFor(run, duplicateB);
        assertThat(unpaidDuplicate.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(unpaidDuplicate.has("paymentTransactionId")).isFalse();

        JsonNode missingInternal = data(mockMvc.perform(get("/api/reconciliation").header("Authorization", finance)
                .param("runId", run.get("id").asText()).param("status", "MISSING_INTERNAL_RECORD")
                .param("size", "100")));
        assertThat(missingInternal).anySatisfy(record -> {
            assertThat(record.get("externalTransactionReference").asText()).isEqualTo("SIM-" + orphanReference);
            assertThat(record.has("invoiceId")).isFalse();
            assertThat(record.get("actualAmount").decimalValue()).isEqualByComparingTo("77.5");
        });

        // Reconciliation only reports. It changed no invoice and no payment.
        assertThat(invoiceRepository.findById(shortPaid.getId()).orElseThrow().getInvoiceStatus())
                .isEqualTo(InvoiceStatus.PAID);
        mockMvc.perform(get("/api/payments/" + shortTx.get("id").asLong() + "/status")
                        .header("Authorization", finance))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void theDetailShowsBothSidesFieldByField() throws Exception {
        Invoice shortPaid = invoice("2000.00");
        JsonNode tx = pay(shortPaid);
        onStatement(tx, shortPaid, "1800.0000", PaymentGateway.Status.SUCCESS);
        long recordId = recordFor(reconcileToday(), shortPaid).get("id").asLong();

        JsonNode detail = data(mockMvc.perform(get("/api/reconciliation/" + recordId)
                .header("Authorization", finance)).andExpect(status().isOk()));

        assertThat(detail.get("record").get("status").asText()).isEqualTo("MISMATCH");
        JsonNode comparison = detail.get("comparison");
        JsonNode amount = null;
        JsonNode invoiceReference = null;
        for (JsonNode row : comparison) {
            if ("amount".equals(row.get("field").asText())) {
                amount = row;
            }
            if ("invoiceReference".equals(row.get("field").asText())) {
                invoiceReference = row;
            }
        }
        assertThat(new BigDecimal(amount.get("internal").asText())).isEqualByComparingTo("2000");
        assertThat(new BigDecimal(amount.get("external").asText())).isEqualByComparingTo("1800");
        assertThat(amount.get("matches").asBoolean()).isFalse();
        assertThat(invoiceReference.get("internal").asText()).isEqualTo(shortPaid.getInvoiceNumber());
        assertThat(invoiceReference.get("matches").asBoolean()).isTrue();
    }

    // ---------- review ----------

    @Test
    void aDiscrepancyIsReviewedWithANoteAndNothingElseChanges() throws Exception {
        Invoice shortPaid = invoice("2000.00");
        JsonNode tx = pay(shortPaid);
        onStatement(tx, shortPaid, "1800.0000", PaymentGateway.Status.SUCCESS);
        long recordId = recordFor(reconcileToday(), shortPaid).get("id").asLong();
        String url = "/api/reconciliation/" + recordId + "/review";

        // A note is required, and only the two decisions are accepted.
        mockMvc.perform(patch(url).header("Authorization", finance).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("note"));
        mockMvc.perform(patch(url).header("Authorization", finance).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"OPEN\",\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch(url).header("Authorization", finance).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"NEEDS_INVESTIGATION\",\"note\":\"Asked the supplier about the 200 short\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.record.reviewStatus").value("NEEDS_INVESTIGATION"))
                .andExpect(jsonPath("$.data.reviewNote").value("Asked the supplier about the 200 short"))
                .andExpect(jsonPath("$.data.reviewedBy").isNumber())
                .andExpect(jsonPath("$.data.record.reviewedAt").exists());
        mockMvc.perform(patch(url).header("Authorization", finance).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"RESOLVED\",\"note\":\"Credit note received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.record.reviewStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data.record.status").value("MISMATCH"));   // the finding itself stays

        assertThat(invoiceRepository.findById(shortPaid.getId()).orElseThrow().getInvoiceStatus())
                .isEqualTo(InvoiceStatus.PAID);
        mockMvc.perform(get("/api/reconciliation").header("Authorization", finance)
                        .param("invoiceId", String.valueOf(shortPaid.getId())).param("reviewStatus", "RESOLVED"))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void aMatchedRecordNeedsNoReview() throws Exception {
        Invoice matched = invoice("1000.00");
        onStatement(pay(matched), matched, "1000.0000", PaymentGateway.Status.SUCCESS);
        long recordId = recordFor(reconcileToday(), matched).get("id").asLong();

        mockMvc.perform(patch("/api/reconciliation/" + recordId + "/review").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"RESOLVED\",\"note\":\"nothing to do\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- runs ----------

    @Test
    void whenTheStatementCannotBeFetchedTheRunFailsWithTheReasonAndNoRecords() throws Exception {
        pay(invoice("100.00"));
        gateway.statementFailure = new PaymentGatewayException(PaymentGatewayException.Kind.UNREACHABLE, null,
                "The payment service could not be reached", null);

        JsonNode run = reconcileToday();

        assertThat(run.get("status").asText()).isEqualTo("FAILED");
        assertThat(run.get("errorMessage").asText()).contains("PAYMENT_SERVICE_UNREACHABLE", "could not be reached");
        assertThat(run.get("totalRecords").asInt()).isZero();
        mockMvc.perform(get("/api/reconciliation").header("Authorization", finance)
                        .param("runId", run.get("id").asText()))
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    @Test
    void withoutARunIdTheLatestRunIsShownAndRunsCanBeListed() throws Exception {
        Invoice matched = invoice("1000.00");
        onStatement(pay(matched), matched, "1000.0000", PaymentGateway.Status.SUCCESS);
        JsonNode run = reconcileToday();

        mockMvc.perform(get("/api/reconciliation").header("Authorization", finance)
                        .param("invoiceId", String.valueOf(matched.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value(run.get("id").asLong()));
        mockMvc.perform(get("/api/reconciliation/runs").header("Authorization", finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(run.get("id").asLong()))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));
        mockMvc.perform(get("/api/reconciliation/runs/999999999").header("Authorization", finance))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reconciliation/999999999").header("Authorization", finance))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidPeriodIsA400() throws Exception {
        mockMvc.perform(post("/api/reconciliation/run").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"2026-11-10\",\"periodTo\":\"2026-11-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("periodFrom must not be after periodTo"));
        mockMvc.perform(post("/api/reconciliation/run").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"periodFrom\":\"2026-11-10\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/reconciliation/run").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"2020-01-01\",\"periodTo\":\"2026-11-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void integrationOperatorsHaveNoAccessToReconciliation() throws Exception {
        String operator = bearer(createUser(RoleName.INTEGRATION_OPERATOR));

        mockMvc.perform(get("/api/reconciliation").header("Authorization", operator))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reconciliation/runs").header("Authorization", operator))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reconciliation/run").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"" + today + "\",\"periodTo\":\"" + today + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(patch("/api/reconciliation/1/review").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"RESOLVED\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reconciliation")).andExpect(status().isUnauthorized());

        String admin = bearer(createUser(RoleName.ADMIN));
        mockMvc.perform(get("/api/reconciliation/runs").header("Authorization", admin)).andExpect(status().isOk());
    }
}
