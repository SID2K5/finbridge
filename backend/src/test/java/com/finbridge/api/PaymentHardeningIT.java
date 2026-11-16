package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Payments under concurrency, against a real MySQL. The point of these tests is that two
 * requests really do run at the same time: every thread waits at a latch and all are
 * released together, and the payment service is made slow so the requests overlap.
 */
@Import(PaymentIT.ScriptedGatewayConfig.class)
class PaymentHardeningIT extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private PaymentIT.ScriptedGateway gateway;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String finance;
    private String operator;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        gateway.reset();
        finance = bearer(createUser(RoleName.FINANCE_MANAGER));
        operator = bearer(createUser(RoleName.INTEGRATION_OPERATOR));
        invoice = createInvoice(createSupplier("Raced " + nextNumber()), "BILL/RACE/" + nextNumber(),
                LocalDate.of(2026, 11, 1), "5000.00");
    }

    /** Fires the same kind of request from THREADS threads at one moment and returns every response. */
    private List<MockHttpServletResponse> together(Callable<MockHttpServletResponse> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return request.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return responses;
    }

    private MockHttpServletResponse pay(String key) throws Exception {
        return mockMvc.perform(post("/api/payments").header("Authorization", finance)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":" + invoice.getId() + "}"))
                .andReturn().getResponse();
    }

    private void slowGateway() {
        for (int i = 0; i < THREADS; i++) {
            gateway.submitScript.add(reference -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new PaymentGateway.Outcome("SIM-" + reference, reference, "x",
                        new java.math.BigDecimal("5000.0000"), "INR", PaymentGateway.Status.SUCCESS, Instant.now(),
                        null);
            });
        }
    }

    private int paymentRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE invoice_id = ?",
                Integer.class, invoice.getId());
    }

    @Test
    void eightSimultaneousPaymentsOfOneInvoiceWithDifferentKeysPayItExactlyOnce() throws Exception {
        slowGateway();

        List<MockHttpServletResponse> responses = together(() -> pay(UUID.randomUUID().toString()));

        assertThat(responses).filteredOn(response -> response.getStatus() == 201).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatus() == 409).hasSize(THREADS - 1);
        assertThat(paymentRows()).isEqualTo(1);
        assertThat(gateway.submitted).hasSize(1);
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getInvoiceStatus())
                .isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void eightSimultaneousCopiesOfOneRequestBecomeOneTransactionAndOneCallToThePaymentService() throws Exception {
        slowGateway();
        String key = UUID.randomUUID().toString();

        List<MockHttpServletResponse> responses = together(() -> pay(key));

        // A double click or a client retry must never see an error: every copy gets the transaction.
        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus()).isEqualTo(201));
        List<Long> ids = new ArrayList<>();
        for (MockHttpServletResponse response : responses) {
            ids.add(objectMapper.readTree(response.getContentAsString()).get("data").get("id").asLong());
        }
        assertThat(ids).containsOnly(ids.get(0));
        assertThat(responses).filteredOn(response -> "false".equals(response.getHeader("Idempotent-Replay")))
                .hasSize(1);
        assertThat(paymentRows()).isEqualTo(1);
        assertThat(gateway.submitted).hasSize(1);
    }

    @Test
    void simultaneousRetriesOfOneFailedPaymentSendItOnce() throws Exception {
        gateway.submitScript.add(reference -> {
            throw new PaymentGatewayException(PaymentGatewayException.Kind.FAULT, "SERVICE_UNAVAILABLE", "down", null);
        });
        long paymentId = objectMapper.readTree(pay(UUID.randomUUID().toString()).getContentAsString())
                .get("data").get("id").asLong();
        slowGateway();

        List<MockHttpServletResponse> responses = together(() -> mockMvc.perform(
                post("/api/payments/" + paymentId + "/retry").header("Authorization", operator))
                .andReturn().getResponse());

        // One retry wins. The others are refused (the payment is no longer FAILED) or told
        // that the record changed under them; none of them sends a second request.
        assertThat(responses).filteredOn(response -> response.getStatus() == 200).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatus() != 200)
                .allSatisfy(response -> assertThat(response.getStatus()).isIn(409, 422));
        assertThat(gateway.submitted).hasSize(2);   // the failed first attempt and exactly one retry
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM payment_transactions WHERE id = ?",
                Integer.class, paymentId)).isEqualTo(2);
    }

    // ---------- retry from the integration log ----------

    private long logRow(String outcome, boolean retryable, Long paymentId) {
        jdbcTemplate.update("INSERT INTO integration_logs (correlation_id, integration_type, operation, outcome, "
                        + "duration_ms, error_code, payment_transaction_id, retryable) VALUES (?, 'SOAP', "
                        + "'SubmitPayment', ?, 5, ?, ?, ?)", UUID.randomUUID().toString(), outcome,
                "SUCCESS".equals(outcome) ? null : "SERVICE_UNAVAILABLE", paymentId, retryable);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM integration_logs", Long.class);
    }

    @Test
    void retryingAFailedPaymentCallFromTheLogUsesThePaymentsOwnRetry() throws Exception {
        gateway.submitScript.add(reference -> {
            throw new PaymentGatewayException(PaymentGatewayException.Kind.FAULT, "SERVICE_UNAVAILABLE", "down", null);
        });
        JsonNode failed = objectMapper.readTree(pay(UUID.randomUUID().toString()).getContentAsString()).get("data");
        long paymentId = failed.get("id").asLong();
        long logId = logRow("FAILURE", true, paymentId);

        mockMvc.perform(post("/api/integrations/logs/" + logId + "/retry").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("PAYMENT_RETRIED"))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));

        // The payment is paid now, so the same log entry cannot be used to pay it again.
        mockMvc.perform(post("/api/integrations/logs/" + logId + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("already SUCCESS")));
        assertThat(gateway.submitted).hasSize(2);
    }

    @Test
    void aSuccessfulOrNonRetryableLogEntryCannotBeRetriedAndFinanceCannotRetryAtAll() throws Exception {
        long succeeded = logRow("SUCCESS", false, null);
        long permanent = logRow("FAILURE", false, null);
        long statementCall = logRow("FAILURE", true, null);

        mockMvc.perform(post("/api/integrations/logs/" + succeeded + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("This call succeeded; there is nothing to retry"));
        mockMvc.perform(post("/api/integrations/logs/" + permanent + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("not retryable")));
        mockMvc.perform(post("/api/integrations/logs/" + statementCall + "/retry").header("Authorization", operator))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("does not belong to a payment")));
        mockMvc.perform(post("/api/integrations/logs/999999999/retry").header("Authorization", operator))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/integrations/logs/" + statementCall + "/retry").header("Authorization", finance))
                .andExpect(status().isForbidden());
        assertThat(gateway.submitted).isEmpty();
    }
}
