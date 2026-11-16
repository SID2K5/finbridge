package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.invoice.SyncStatus;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The payment flow against a real MySQL: HTTP, security, idempotency, the invoice lock, the
 * state machine and what ends up in the tables. Only the payment service is replaced, by a
 * scripted gateway, so that every answer (including a lost one) can be produced on demand.
 * The SOAP wire format is covered by SoapPaymentAdapterTest and the simulator's own tests.
 */
@Import(PaymentIT.ScriptedGatewayConfig.class)
class PaymentIT extends AbstractIntegrationTest {

    /** Answers submit and inquire calls from a queue of scripted behaviours and records every call. */
    static class ScriptedGateway implements PaymentGateway {

        final Deque<Function<String, Outcome>> submitScript = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final Deque<Function<String, Outcome>> inquireScript = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final List<Instruction> submitted = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> inquired = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<StatementEntry> statementEntries = new ArrayList<>();
        volatile RuntimeException statementFailure;

        void reset() {
            submitScript.clear();
            inquireScript.clear();
            submitted.clear();
            inquired.clear();
            statementEntries.clear();
            statementFailure = null;
        }

        @Override
        public Outcome submit(Instruction instruction, CallContext context) {
            submitted.add(instruction);
            Function<String, Outcome> next = submitScript.poll();
            return next != null ? next.apply(instruction.transactionReference())
                    : outcome(instruction.transactionReference(), Status.SUCCESS, null);
        }

        @Override
        public Outcome inquire(String transactionReference, CallContext context) {
            inquired.add(transactionReference);
            Function<String, Outcome> next = inquireScript.poll();
            return next != null ? next.apply(transactionReference)
                    : outcome(transactionReference, Status.SUCCESS, null);
        }

        @Override
        public List<StatementEntry> statement(LocalDate from, LocalDate to) {
            if (statementFailure != null) {
                throw statementFailure;
            }
            return List.copyOf(statementEntries);
        }

        @Override
        public void ping() {
        }
    }

    @TestConfiguration
    static class ScriptedGatewayConfig {

        @Bean
        @Primary
        ScriptedGateway scriptedGateway() {
            return new ScriptedGateway();
        }
    }

    private static PaymentGateway.Outcome outcome(String reference, PaymentGateway.Status status, String reason) {
        boolean paid = status == PaymentGateway.Status.SUCCESS;
        return new PaymentGateway.Outcome("SIM-" + reference, reference, "BILL", new java.math.BigDecimal(
                paid ? "1180.0000" : "0.0000"), "INR", status, Instant.parse("2026-11-10T09:30:01Z"), reason);
    }

    private static Function<String, PaymentGateway.Outcome> answer(PaymentGateway.Status status, String reason) {
        return reference -> outcome(reference, status, reason);
    }

    private static Function<String, PaymentGateway.Outcome> fail(PaymentGatewayException.Kind kind, String code) {
        return reference -> {
            throw new PaymentGatewayException(kind, code, "scripted " + kind, null);
        };
    }

    @Autowired
    private ScriptedGateway gateway;

    private String finance;
    private String operator;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        gateway.reset();
        finance = bearer(createUser(RoleName.FINANCE_MANAGER));
        operator = bearer(createUser(RoleName.INTEGRATION_OPERATOR));
        invoice = createInvoice(createSupplier("Payee " + nextNumber()), "BILL/PAY/" + nextNumber(),
                LocalDate.of(2026, 11, 1), "1180.00");
    }

    private ResultActions pay(Long invoiceId, String key, String token) throws Exception {
        var request = post("/api/payments").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":" + invoiceId + "}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private InvoiceStatus invoiceStatus() {
        return invoiceRepository.findById(invoice.getId()).orElseThrow().getInvoiceStatus();
    }

    // ---------- create ----------

    @Test
    void aSuccessfulPaymentPaysTheInvoice() throws Exception {
        JsonNode payment = data(pay(invoice.getId(), key(), finance)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.invoiceNumber").value(invoice.getInvoiceNumber()))
                .andExpect(jsonPath("$.data.requestedAmount").value(1180.00))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.attemptCount").value(1))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist()));

        assertThat(payment.get("transactionReference").asText()).matches("FB-\\d{8}-[2-9A-HJ-NP-Z]{8}");
        assertThat(payment.get("externalTransactionId").asText()).startsWith("SIM-FB-");
        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAID);

        PaymentGateway.Instruction sent = gateway.submitted.get(0);
        assertThat(sent.invoiceReference()).isEqualTo(invoice.getInvoiceNumber());
        assertThat(sent.supplierReference()).isEqualTo("SUP-" + invoice.getSupplier().getOdooPartnerId());
        assertThat(sent.amount()).isEqualByComparingTo("1180.00");

        mockMvc.perform(get("/api/invoices/" + invoice.getId()).header("Authorization", finance))
                .andExpect(jsonPath("$.data.invoiceStatus").value("PAID"))
                .andExpect(jsonPath("$.data.payments[0].status").value("SUCCESS"));
        mockMvc.perform(get("/api/payments/" + payment.get("id").asLong() + "/status")
                        .header("Authorization", finance))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void theSameKeyAndBodyReplaysTheTransactionAndSendsNothing() throws Exception {
        String key = key();
        JsonNode first = data(pay(invoice.getId(), key, finance).andExpect(status().isCreated()));

        JsonNode second = data(pay(invoice.getId(), key, finance)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true")));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(second.get("transactionReference").asText()).isEqualTo(first.get("transactionReference").asText());
        assertThat(gateway.submitted).hasSize(1);
    }

    @Test
    void theSameKeyForADifferentInvoiceIsA409() throws Exception {
        String key = key();
        pay(invoice.getId(), key, finance).andExpect(status().isCreated());
        Invoice other = createInvoice(invoice.getSupplier(), "BILL/PAY/" + nextNumber(), LocalDate.of(2026, 11, 2),
                "50.00");

        pay(other.getId(), key, finance)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        assertThat(gateway.submitted).hasSize(1);
    }

    @Test
    void anInvoiceThatIsPaidCannotBePaidAgainWithANewKey() throws Exception {
        pay(invoice.getId(), key(), finance).andExpect(status().isCreated());

        pay(invoice.getId(), key(), finance)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        assertThat(gateway.submitted).hasSize(1);
    }

    @Test
    void aMissingOrMalformedIdempotencyKeyIsA400() throws Exception {
        pay(invoice.getId(), null, finance).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        pay(invoice.getId(), "short", finance).andExpect(status().isBadRequest());
        pay(invoice.getId(), "has spaces in it !!", finance).andExpect(status().isBadRequest());
        assertThat(gateway.submitted).isEmpty();
        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void anUnknownInvoiceIsA404AndAStaleInvoiceIsA422() throws Exception {
        pay(999_999_999L, key(), finance).andExpect(status().isNotFound());

        invoice.setSyncStatus(SyncStatus.STALE);
        invoiceRepository.save(invoice);
        pay(invoice.getId(), key(), finance)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("STALE")));
        assertThat(gateway.submitted).isEmpty();
    }

    // ---------- business outcomes ----------

    @Test
    void aRejectedPaymentIsFinalAndCannotBeRetried() throws Exception {
        gateway.submitScript.add(answer(PaymentGateway.Status.REJECTED, "ACCOUNT_BLOCKED"));

        long id = data(pay(invoice.getId(), key(), finance)
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.failureReason").value("ACCOUNT_BLOCKED"))).get("id").asLong();

        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAYMENT_FAILED);
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity());
        assertThat(gateway.submitted).hasSize(1);
        // The invoice is free again: a new payment with a new key is allowed.
        pay(invoice.getId(), key(), finance).andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void aFailedPaymentCanBeRetriedAsANewAttemptWithItsOwnKey() throws Exception {
        gateway.submitScript.add(answer(PaymentGateway.Status.FAILED, "PROCESSING_ERROR"));
        String key = key();
        long id = data(pay(invoice.getId(), key, finance)
                .andExpect(jsonPath("$.data.status").value("FAILED"))).get("id").asLong();
        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAYMENT_FAILED);

        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.attemptCount").value(2))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist());

        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(gateway.submitted).hasSize(2);
        assertThat(gateway.submitted.get(0).idempotencyKey()).isEqualTo(key);
        assertThat(gateway.submitted.get(1).idempotencyKey()).isNotEqualTo(key).hasSize(64);
        assertThat(gateway.submitted.get(1).transactionReference())
                .isEqualTo(gateway.submitted.get(0).transactionReference());
    }

    @Test
    void retriesStopAtTheAttemptLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            gateway.submitScript.add(answer(PaymentGateway.Status.FAILED, "PROCESSING_ERROR"));
        }
        long id = data(pay(invoice.getId(), key(), finance)).get("id").asLong();
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(jsonPath("$.data.attemptCount").value(2));
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(jsonPath("$.data.attemptCount").value(3));

        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("attempt limit")));
        assertThat(gateway.submitted).hasSize(3);
    }

    @Test
    void aPendingPaymentIsSettledByAStatusCheck() throws Exception {
        gateway.submitScript.add(answer(PaymentGateway.Status.PENDING, null));
        long id = data(pay(invoice.getId(), key(), finance)
                .andExpect(jsonPath("$.data.status").value("PENDING"))).get("id").asLong();
        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAYMENT_IN_PROGRESS);
        // While it is pending, the invoice cannot get a second payment.
        pay(invoice.getId(), key(), finance).andExpect(status().isConflict());

        gateway.inquireScript.add(answer(PaymentGateway.Status.PENDING, null));
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- lost answers and faults ----------

    @Test
    void aTimeoutIsUnknownNotFailedAndIsResolvedByInquiryWithoutSendingAgain() throws Exception {
        gateway.submitScript.add(fail(PaymentGatewayException.Kind.TIMEOUT, null));

        long id = data(pay(invoice.getId(), key(), finance)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("TIMEOUT_UNKNOWN"))).get("id").asLong();

        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAYMENT_IN_PROGRESS);   // not PAYMENT_FAILED
        pay(invoice.getId(), key(), finance).andExpect(status().isConflict());        // no second payment

        // "retry" on a timeout asks first; the service says it was paid, so nothing is sent again.
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.attemptCount").value(1));

        assertThat(gateway.submitted).hasSize(1);
        assertThat(gateway.inquired).hasSize(1);
        assertThat(invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void afterATimeoutTheRequestIsSentAgainOnlyIfTheServiceNeverSawItAndWithTheSameKey() throws Exception {
        gateway.submitScript.add(fail(PaymentGatewayException.Kind.TIMEOUT, null));
        String key = key();
        long id = data(pay(invoice.getId(), key, finance)).get("id").asLong();

        gateway.inquireScript.add(fail(PaymentGatewayException.Kind.FAULT, "UNKNOWN_TRANSACTION"));
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.attemptCount").value(1));   // the same attempt, not a new one

        assertThat(gateway.submitted).hasSize(2);
        assertThat(gateway.submitted.get(1).idempotencyKey()).isEqualTo(key);
    }

    @Test
    void whenTheInquiryItselfFailsNothingChanges() throws Exception {
        gateway.submitScript.add(fail(PaymentGatewayException.Kind.TIMEOUT, null));
        long id = data(pay(invoice.getId(), key(), finance)).get("id").asLong();

        gateway.inquireScript.add(fail(PaymentGatewayException.Kind.TIMEOUT, null));
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"));
        gateway.inquireScript.add(fail(PaymentGatewayException.Kind.UNREACHABLE, null));
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", operator))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));

        mockMvc.perform(get("/api/payments/" + id + "/status").header("Authorization", operator))
                .andExpect(jsonPath("$.data.status").value("TIMEOUT_UNKNOWN"));
        assertThat(gateway.submitted).hasSize(1);
    }

    @Test
    void anOutageOrAnUnreachableServiceFailsThePaymentAndItCanBeRetried() throws Exception {
        gateway.submitScript.add(fail(PaymentGatewayException.Kind.FAULT, "SERVICE_UNAVAILABLE"));
        long id = data(pay(invoice.getId(), key(), finance)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value(
                        org.hamcrest.Matchers.startsWith("SERVICE_UNAVAILABLE")))).get("id").asLong();

        gateway.submitScript.add(fail(PaymentGatewayException.Kind.UNREACHABLE, null));
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value(
                        org.hamcrest.Matchers.startsWith("PAYMENT_SERVICE_UNREACHABLE")));
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", operator))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    // ---------- reading and roles ----------

    @Test
    void paymentsCanBeListedAndFiltered() throws Exception {
        long id = data(pay(invoice.getId(), key(), finance)).get("id").asLong();

        mockMvc.perform(get("/api/payments").header("Authorization", operator)
                        .param("invoiceId", String.valueOf(invoice.getId())).param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].supplierName").value(invoice.getSupplier().getName()));
        mockMvc.perform(get("/api/payments").header("Authorization", operator)
                        .param("invoiceId", String.valueOf(invoice.getId())).param("status", "FAILED"))
                .andExpect(jsonPath("$.meta.totalElements").value(0));
        mockMvc.perform(get("/api/payments").header("Authorization", operator).param("sort", "idempotencyKey,asc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/payments/" + id).header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.id").value(id))
                .andExpect(jsonPath("$.data.attempts").isArray());
        mockMvc.perform(get("/api/payments/999999999").header("Authorization", operator))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingAndRetryingAreHeldByDifferentRoles() throws Exception {
        pay(invoice.getId(), key(), operator)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceId\":1}")).andExpect(status().isUnauthorized());

        gateway.submitScript.add(answer(PaymentGateway.Status.FAILED, "PROCESSING_ERROR"));
        long id = data(pay(invoice.getId(), key(), finance)).get("id").asLong();
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", finance))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/payments/" + id + "/status-check").header("Authorization", finance))
                .andExpect(status().isForbidden());
        // An admin may do both.
        String admin = bearer(createUser(RoleName.ADMIN));
        mockMvc.perform(post("/api/payments/" + id + "/retry").header("Authorization", admin))
                .andExpect(status().isOk());
        assertThat(gateway.submitted).hasSize(2);
    }
}
