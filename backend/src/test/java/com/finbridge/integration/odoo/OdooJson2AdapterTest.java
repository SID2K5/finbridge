package com.finbridge.integration.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogService;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The JSON-2 wire format, checked against a mock HTTP server. No Odoo and no database. */
class OdooJson2AdapterTest {

    private static final String API_KEY = "0123456789abcdef0123456789abcdef01234567";
    private static final String BASE = "http://odoo.test:8069";

    private final IntegrationLogService integrationLog = mock(IntegrationLogService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private OdooJson2Adapter adapter(String apiKey) {
        OdooProperties properties = new OdooProperties(BASE + "/", "finbridge", apiKey, Duration.ofSeconds(5),
                Duration.ofSeconds(30), 3, Duration.ZERO, 100);
        return new OdooJson2Adapter(properties, integrationLog, builder);
    }

    private IntegrationLogEntry lastLog() {
        ArgumentCaptor<IntegrationLogEntry> captor = ArgumentCaptor.forClass(IntegrationLogEntry.class);
        verify(integrationLog, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void vendorBillsAreRequestedTheJson2WayAndParsedExactly() {
        server.expect(requestTo(BASE + "/json/2/account.move/search_read"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "bearer " + API_KEY))
                .andExpect(header("X-Odoo-Database", "finbridge"))
                .andExpect(content().json("""
                        {
                          "domain": [["move_type", "=", "in_invoice"], ["state", "=", "posted"],
                                     ["write_date", ">=", "2026-11-10 09:30:00"]],
                          "fields": ["id", "name", "ref", "partner_id", "invoice_date", "invoice_date_due",
                                     "amount_untaxed", "amount_tax", "amount_total", "currency_id",
                                     "payment_state", "invoice_origin", "write_date"],
                          "offset": 200, "limit": 100, "order": "write_date asc, id asc"
                        }""", true))
                .andRespond(withSuccess("""
                        [
                          {"id": 17, "name": "BILL/2026/11/0001", "ref": "INV-778", "partner_id": [42, "Acme Supplies"],
                           "invoice_date": "2026-11-05", "invoice_date_due": "2026-12-05",
                           "amount_untaxed": 50000.1, "amount_tax": 9000.02, "amount_total": 59000.12,
                           "currency_id": [20, "INR"], "payment_state": "not_paid", "invoice_origin": "P00012",
                           "write_date": "2026-11-10 09:31:07"},
                          {"id": 18, "name": "BILL/2026/11/0002", "ref": false, "partner_id": false,
                           "invoice_date": false, "invoice_date_due": false,
                           "amount_untaxed": 0.0, "amount_tax": 0.0, "amount_total": 0.0,
                           "currency_id": false, "payment_state": "not_paid", "invoice_origin": false,
                           "write_date": "2026-11-10 09:32:00"}
                        ]""", MediaType.APPLICATION_JSON));

        List<OdooVendorBill> bills = adapter(API_KEY)
                .fetchVendorBills(Instant.parse("2026-11-10T09:30:00.450Z"), 200, 100, "sync-run-5");

        server.verify();
        assertThat(bills).hasSize(2);
        OdooVendorBill bill = bills.get(0);
        assertThat(bill.id()).isEqualTo(17);
        assertThat(bill.number()).isEqualTo("BILL/2026/11/0001");
        assertThat(bill.partnerId()).isEqualTo(42L);
        assertThat(bill.invoiceDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(bill.totalAmount().toPlainString()).isEqualTo("59000.12");   // exact, never a double
        assertThat(bill.currency()).isEqualTo("INR");
        assertThat(bill.origin()).isEqualTo("P00012");
        assertThat(bill.writeDate()).isEqualTo(Instant.parse("2026-11-10T09:31:07Z"));
        // Odoo's "false" for an empty field becomes null, not the text "false".
        OdooVendorBill empty = bills.get(1);
        assertThat(empty.vendorReference()).isNull();
        assertThat(empty.partnerId()).isNull();
        assertThat(empty.invoiceDate()).isNull();
        assertThat(empty.currency()).isNull();

        IntegrationLogEntry logged = lastLog();
        assertThat(logged.outcome()).isEqualTo(IntegrationLogEntry.Outcome.SUCCESS);
        assertThat(logged.operation()).isEqualTo("account.move/search_read");
        assertThat(logged.requestReference()).isEqualTo("sync-run-5");
        assertThat(logged.httpStatus()).isEqualTo(200);
        assertThat(logged.requestPayload()).doesNotContain(API_KEY);
    }

    @Test
    void suppliersIncludeArchivedPartnersAndAFirstSyncHasNoDateFilter() {
        server.expect(requestTo(BASE + "/json/2/res.partner/search_read"))
                .andExpect(content().json("""
                        {"domain": [["supplier_rank", ">", 0]], "offset": 0, "limit": 50,
                         "context": {"active_test": false}}"""))
                .andRespond(withSuccess("""
                        [{"id": 42, "name": "Acme Supplies", "display_name": "Acme Supplies", "vat": "27AAAPL1234C1ZV",
                          "email": false, "phone": "+91 22 5550 0100", "country_code": "IN", "active": false,
                          "write_date": "2026-11-01 08:00:00"}]""", MediaType.APPLICATION_JSON));

        List<OdooPartner> partners = adapter(API_KEY).fetchSuppliers(null, 0, 50, null);

        server.verify();
        assertThat(partners).containsExactly(new OdooPartner(42, "Acme Supplies", "27AAAPL1234C1ZV", null,
                "+91 22 5550 0100", "IN", false, Instant.parse("2026-11-01T08:00:00Z")));
    }

    @Test
    void aRefusedKeyIsNotRetriedAndTheTracebackNeverLeavesTheAdapter() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/json/2/res.partner/search_read"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"name": "werkzeug.exceptions.Unauthorized", "message": "Invalid apikey",
                         "arguments": ["Invalid apikey"], "context": {},
                         "debug": "Traceback (most recent call last):\\n  File \\"/usr/lib/python3/odoo/http.py\\""}"""));

        assertThatThrownBy(() -> adapter(API_KEY).ping())
                .isInstanceOfSatisfying(OdooException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ODOO_AUTH");
                    assertThat(ex.getHttpStatus()).isEqualTo(401);
                    assertThat(ex.getMessage()).contains("Invalid apikey").doesNotContain("Traceback");
                });

        server.verify();
        IntegrationLogEntry logged = lastLog();
        assertThat(logged.outcome()).isEqualTo(IntegrationLogEntry.Outcome.FAILURE);
        assertThat(logged.errorCode()).isEqualTo("ODOO_AUTH");
        assertThat(logged.retryable()).isFalse();
        assertThat(logged.responsePayload()).contains("Invalid apikey").doesNotContain("Traceback", "http.py");
    }

    @Test
    void aServerErrorIsRetriedUpToTheAttemptLimit() {
        server.expect(ExpectedCount.times(3), requestTo(BASE + "/json/2/res.partner/search_read"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad gateway</html>"));

        assertThatThrownBy(() -> adapter(API_KEY).ping())
                .isInstanceOfSatisfying(OdooException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ODOO_SERVER_ERROR"));

        server.verify();
        verify(integrationLog, times(3)).record(any());
    }

    @Test
    void aTimeoutIsLoggedAsTimeoutAndRetriedThenSucceeds() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/json/2/res.partner/search_read"))
                .andRespond(request -> {
                    throw new HttpTimeoutException("request timed out");
                });
        server.expect(ExpectedCount.once(), requestTo(BASE + "/json/2/res.partner/search_read"))
                .andRespond(withSuccess("[{\"id\": 1}]", MediaType.APPLICATION_JSON));

        adapter(API_KEY).ping();

        server.verify();
        ArgumentCaptor<IntegrationLogEntry> captor = ArgumentCaptor.forClass(IntegrationLogEntry.class);
        verify(integrationLog, times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(0).outcome()).isEqualTo(IntegrationLogEntry.Outcome.TIMEOUT);
        assertThat(captor.getAllValues().get(0).errorCode()).isEqualTo("ODOO_TIMEOUT");
        assertThat(captor.getAllValues().get(0).retryable()).isTrue();
        assertThat(captor.getAllValues().get(1).outcome()).isEqualTo(IntegrationLogEntry.Outcome.SUCCESS);
    }

    @Test
    void anOdooValidationErrorIsNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/json/2/account.move/search_read"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\": \"builtins.ValueError\", \"message\": \"Invalid field 'nope' on model 'account.move'\"}"));

        assertThatThrownBy(() -> adapter(API_KEY).fetchVendorBills(null, 0, 10, null))
                .isInstanceOfSatisfying(OdooException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ODOO_REJECTED");
                    assertThat(ex.getMessage()).contains("Invalid field 'nope'");
                });
        server.verify();
    }

    @Test
    void withoutAnApiKeyNoRequestIsSent() {
        OdooJson2Adapter adapter = adapter("  ");

        assertThat(adapter.isConfigured()).isFalse();
        assertThatThrownBy(adapter::ping).isInstanceOfSatisfying(OdooException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("ODOO_NOT_CONFIGURED"));
        server.verify();   // no expectations were set, so any request would have failed the test
        verify(integrationLog, never()).record(any());
    }

    @Test
    void theApiKeyIsNeverPrintedWithTheSettings() {
        OdooProperties properties = new OdooProperties(BASE, "finbridge", API_KEY, Duration.ofSeconds(5),
                Duration.ofSeconds(30), 3, Duration.ZERO, 100);

        assertThat(properties.toString()).doesNotContain(API_KEY).contains("apiKey=configured");
    }
}
