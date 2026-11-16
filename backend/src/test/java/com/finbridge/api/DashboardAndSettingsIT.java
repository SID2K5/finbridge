package com.finbridge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.finbridge.invoice.Invoice;
import com.finbridge.support.AbstractIntegrationTest;
import com.finbridge.user.RoleName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The dashboard figures and the runtime settings against a real MySQL. The database is
 * shared with the other tests, so the dashboard assertions compare each figure with the
 * same figure computed by plain SQL, or check how it moves when this test adds data.
 */
@Import(PaymentIT.ScriptedGatewayConfig.class)
class DashboardAndSettingsIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaymentIT.ScriptedGateway gateway;

    private String admin;
    private String finance;
    private String operator;

    @BeforeEach
    void setUp() {
        gateway.reset();
        admin = bearer(createUser(RoleName.ADMIN));
        finance = bearer(createUser(RoleName.FINANCE_MANAGER));
        operator = bearer(createUser(RoleName.INTEGRATION_OPERATOR));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode summary() throws Exception {
        return data(mockMvc.perform(get("/api/dashboard/summary").header("Authorization", operator))
                .andExpect(status().isOk()));
    }

    private static BigDecimal amountFor(JsonNode list, String currency) {
        for (JsonNode entry : list) {
            if (currency.equals(entry.get("currency").asText())) {
                return entry.get("amount").decimalValue();
            }
        }
        return BigDecimal.ZERO;
    }

    private Invoice payNewInvoice(String total) throws Exception {
        Invoice invoice = createInvoice(createSupplier("Dash " + nextNumber()), "BILL/DASH/" + nextNumber(),
                LocalDate.of(2026, 11, 1), total);
        mockMvc.perform(post("/api/payments").header("Authorization", finance)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":" + invoice.getId() + "}"))
                .andExpect(status().isCreated());
        return invoice;
    }

    // ---------- dashboard ----------

    @Test
    void theSummaryEqualsWhatTheTablesHold() throws Exception {
        payNewInvoice("750.00");

        JsonNode summary = summary();

        assertThat(summary.get("invoiceCount").asLong())
                .isEqualTo(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Long.class));
        assertThat(amountFor(summary.get("invoiceValue"), "INR")).isEqualByComparingTo(jdbcTemplate.queryForObject(
                "SELECT SUM(total_amount) FROM invoices WHERE currency = 'INR'", BigDecimal.class));
        assertThat(amountFor(summary.get("paidAmount"), "INR")).isEqualByComparingTo(jdbcTemplate.queryForObject(
                "SELECT SUM(processed_amount) FROM payment_transactions WHERE status = 'SUCCESS' AND currency = 'INR'",
                BigDecimal.class));
        assertThat(summary.get("paymentsByStatus").get("SUCCESS").asLong()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE status = 'SUCCESS'", Long.class));
        assertThat(summary.get("paymentCount").asLong())
                .isEqualTo(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions", Long.class));
    }

    @Test
    void anOpenInvoiceAddsToOpenPayableUntilItIsPaid() throws Exception {
        BigDecimal before = amountFor(summary().get("openPayable"), "INR");
        Invoice open = createInvoice(createSupplier("Dash " + nextNumber()), "BILL/DASH/" + nextNumber(),
                LocalDate.of(2026, 11, 1), "1234.50");
        assertThat(amountFor(summary().get("openPayable"), "INR")).isEqualByComparingTo(before.add(
                new BigDecimal("1234.50")));

        mockMvc.perform(post("/api/payments").header("Authorization", finance)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":" + open.getId() + "}"))
                .andExpect(status().isCreated());

        assertThat(amountFor(summary().get("openPayable"), "INR")).isEqualByComparingTo(before);
    }

    @Test
    void theOtherDashboardEndpointsAnswerWithRealData() throws Exception {
        payNewInvoice("100.00");

        JsonNode transactions = data(mockMvc.perform(get("/api/dashboard/transactions")
                .header("Authorization", operator)).andExpect(status().isOk()));
        assertThat(transactions).anySatisfy(row -> {
            assertThat(row.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(row.get("count").asLong()).isPositive();
        });
        // A window in the past holds nothing: an empty list, not zeros.
        mockMvc.perform(get("/api/dashboard/transactions").header("Authorization", operator)
                        .param("from", "2001-01-01T00:00:00Z").param("to", "2001-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        JsonNode trend = data(mockMvc.perform(get("/api/dashboard/invoice-trend").header("Authorization", operator)
                .param("days", "7")).andExpect(status().isOk()));
        assertThat(trend).hasSize(7);
        assertThat(trend.get(6).get("date").asText()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(trend.get(6).get("count").asLong()).isPositive();   // this test created an invoice today
        mockMvc.perform(get("/api/dashboard/invoice-trend").header("Authorization", operator).param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/dashboard/recent-activity").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].requestPayload").doesNotExist());
    }

    @Test
    void theReconciliationSummaryIsForFinanceAndAdminsOnly() throws Exception {
        mockMvc.perform(get("/api/dashboard/reconciliation-summary").header("Authorization", operator))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/reconciliation-summary").header("Authorization", finance))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    // ---------- settings ----------

    @Test
    void settingsShowConnectionsAndOnlyWhetherEachSecretIsSet() throws Exception {
        String body = mockMvc.perform(get("/api/integrations/config").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings[?(@.key == 'payment.max-attempts')].defaultValue").value("3"))
                .andExpect(jsonPath("$.data.connections[0].url").value("http://odoo.invalid:8069"))
                .andExpect(jsonPath("$.data.secrets[?(@.name == 'ODOO_API_KEY')].configured").value(false))
                .andExpect(jsonPath("$.data.secrets[?(@.name == 'JWT_SECRET')].configured").value(true))
                .andReturn().getResponse().getContentAsString();

        // The test profile's JWT secret must not appear anywhere in the answer.
        assertThat(body).doesNotContain("test-only-secret");
    }

    @Test
    void anAdminChangesTheAttemptLimitAndItAppliesAtOnce() throws Exception {
        gateway.submitScript.add(reference -> {
            throw new com.finbridge.integration.soap.PaymentGatewayException(
                    com.finbridge.integration.soap.PaymentGatewayException.Kind.FAULT, "SERVICE_UNAVAILABLE",
                    "scripted", null);
        });
        Invoice invoice = createInvoice(createSupplier("Dash " + nextNumber()), "BILL/DASH/" + nextNumber(),
                LocalDate.of(2026, 11, 1), "10.00");
        long paymentId = data(mockMvc.perform(post("/api/payments").header("Authorization", finance)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":" + invoice.getId() + "}")))
                .get("id").asLong();

        try {
            mockMvc.perform(put("/api/integrations/config/payment.max-attempts").header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.value").value("1"))
                    .andExpect(jsonPath("$.data.overridden").value(true))
                    .andExpect(jsonPath("$.data.updatedBy").isNumber());

            // One attempt was made and the limit is now one: the retry is refused.
            mockMvc.perform(post("/api/payments/" + paymentId + "/retry").header("Authorization", operator))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("attempt limit of 1")));
        } finally {
            mockMvc.perform(put("/api/integrations/config/payment.max-attempts").header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"3\"}"));
        }
        mockMvc.perform(post("/api/payments/" + paymentId + "/retry").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void settingsAreValidatedAndReservedForAdmins() throws Exception {
        mockMvc.perform(put("/api/integrations/config/payment.max-attempts").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("payment.max-attempts must be between 1 and 10"));
        mockMvc.perform(put("/api/integrations/config/payment.max-attempts").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"many\"}"))
                .andExpect(status().isBadRequest());
        // A secret cannot be smuggled in as a setting: unknown keys do not exist.
        mockMvc.perform(put("/api/integrations/config/odoo.api-key").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"abc\"}"))
                .andExpect(status().isNotFound());

        for (String token : new String[] {finance, operator}) {
            mockMvc.perform(get("/api/integrations/config").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/integrations/config/payment.max-attempts").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"5\"}"))
                    .andExpect(status().isForbidden());
        }
    }
}
