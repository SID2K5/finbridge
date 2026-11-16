package com.finbridge.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The simulator over real HTTP with hand-written SOAP envelopes, exactly as SoapUI or the
 * FinBridge backend would call it. Nothing is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.api-key=test-simulator-key-0123456789",
        "simulator.slow-delay=1500ms"})
class SimulatorIT {

    private static final String KEY = "test-simulator-key-0123456789";
    private static final String NS = "http://finbridge.com/payments/v1";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------- helpers ----------

    private record Reply(int status, String body) {

        String tag(String name) {
            Matcher matcher = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>([^<]*)</").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        int count(String name) {
            Matcher matcher = Pattern.compile("<(?:\\w+:)?" + name + "[\\s>]").matcher(body);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
    }

    private static String envelope(String payload) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:pay=\"" + NS
                + "\"><soapenv:Header/><soapenv:Body>" + payload + "</soapenv:Body></soapenv:Envelope>";
    }

    private static String submitXml(String reference, String invoice, String amount, String key) {
        return envelope("<pay:SubmitPaymentRequest>"
                + "<pay:TransactionReference>" + reference + "</pay:TransactionReference>"
                + "<pay:InvoiceReference>" + invoice + "</pay:InvoiceReference>"
                + "<pay:SupplierReference>SUP-42</pay:SupplierReference>"
                + "<pay:RequestedAmount>" + amount + "</pay:RequestedAmount>"
                + "<pay:Currency>INR</pay:Currency>"
                + "<pay:RequestTimestamp>2026-11-10T09:30:00Z</pay:RequestTimestamp>"
                + "<pay:IdempotencyKey>" + key + "</pay:IdempotencyKey>"
                + "</pay:SubmitPaymentRequest>");
    }

    private static String statusXml(String reference) {
        return envelope("<pay:GetPaymentStatusRequest><pay:TransactionReference>" + reference
                + "</pay:TransactionReference></pay:GetPaymentStatusRequest>");
    }

    private Reply soap(String xml, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws"))
                .header("Content-Type", "text/xml; charset=utf-8").header("SOAPAction", "\"\"")
                .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(xml)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply soap(String xml) throws Exception {
        return soap(xml, Duration.ofSeconds(20));
    }

    private Reply rest(String method, String path, String json, boolean withKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (withKey) {
            builder.header("X-Sim-Api-Key", KEY);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private void scenario(String invoice, String scenario) throws Exception {
        assertThat(rest("PUT", "/sim/scenarios/" + invoice, "{\"scenario\":\"" + scenario + "\"}", true).status())
                .isEqualTo(200);
    }

    private int recordedFor(String invoice) throws Exception {
        String body = rest("GET", "/sim/payments?invoiceReference=" + invoice, null, true).body();
        Matcher matcher = Pattern.compile("\"externalTransactionId\"").matcher(body);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String ref() {
        return "FB-20261110-T" + SEQUENCE.incrementAndGet();
    }

    private static String invoice() {
        return "BILL/2026/11/" + SEQUENCE.incrementAndGet();
    }

    // ---------- contract ----------

    @Test
    void theWsdlIsGeneratedFromTheSchemaWithThreeOperations() throws Exception {
        HttpResponse<String> wsdl = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/ws/payments.wsdl")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("PaymentsPort", "name=\"SubmitPayment\"", "name=\"GetPaymentStatus\"",
                "name=\"GetSettlementStatement\"", "targetNamespace=\"" + NS + "\"",
                "http://localhost:" + port + "/ws");
    }

    // ---------- submit ----------

    @Test
    void aPaymentSucceedsByDefault() throws Exception {
        String reference = ref();
        String invoice = invoice();

        Reply reply = soap(submitXml(reference, invoice, "50000.0000", "key-" + reference));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.tag("PaymentStatus")).isEqualTo("SUCCESS");
        assertThat(reply.tag("TransactionReference")).isEqualTo(reference);
        assertThat(reply.tag("InvoiceReference")).isEqualTo(invoice);
        assertThat(reply.tag("ProcessedAmount")).isEqualTo("50000.0000");
        assertThat(reply.tag("Currency")).isEqualTo("INR");
        assertThat(reply.tag("ExternalTransactionId")).startsWith("SIM-");
        assertThat(reply.tag("FailureReason")).isNull();
    }

    @Test
    void theSameKeyAndPayloadReturnsTheOriginalResultAndCreatesNothingNew() throws Exception {
        String reference = ref();
        String invoice = invoice();
        String xml = submitXml(reference, invoice, "120.50", "key-" + reference);

        Reply first = soap(xml);
        Reply second = soap(xml);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.tag("ExternalTransactionId")).isEqualTo(first.tag("ExternalTransactionId"));
        assertThat(second.tag("ResponseTimestamp")).isEqualTo(first.tag("ResponseTimestamp"));
        assertThat(recordedFor(invoice)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsAnIdempotencyConflict() throws Exception {
        String reference = ref();
        String invoice = invoice();
        soap(submitXml(reference, invoice, "120.50", "key-" + reference));

        Reply conflict = soap(submitXml(reference, invoice, "999.00", "key-" + reference));

        assertThat(conflict.status()).isEqualTo(500);
        assertThat(conflict.tag("faultcode")).endsWith("Client");
        assertThat(conflict.tag("ErrorCode")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(recordedFor(invoice)).isEqualTo(1);
    }

    @Test
    void aReferenceThatAlreadySucceededCannotBeSubmittedAgainUnderANewKey() throws Exception {
        String reference = ref();
        String invoice = invoice();
        soap(submitXml(reference, invoice, "120.50", "key-a-" + reference));

        Reply conflict = soap(submitXml(reference, invoice, "120.50", "key-b-" + reference));

        assertThat(conflict.tag("ErrorCode")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(recordedFor(invoice)).isEqualTo(1);
    }

    // ---------- scenarios ----------

    @Test
    void failedThenRetriedUnderANewKeySucceedsOnTheSameRecord() throws Exception {
        String reference = ref();
        String invoice = invoice();
        scenario(invoice, "FAILED");

        Reply failed = soap(submitXml(reference, invoice, "700.00", "key-1-" + reference));
        assertThat(failed.status()).isEqualTo(200);     // a business failure is a normal response, not a fault
        assertThat(failed.tag("PaymentStatus")).isEqualTo("FAILED");
        assertThat(failed.tag("FailureReason")).isEqualTo("PROCESSING_ERROR");
        assertThat(failed.tag("ProcessedAmount")).isEqualTo("0.0000");

        assertThat(rest("DELETE", "/sim/scenarios/" + invoice, null, true).status()).isEqualTo(204);
        Reply retried = soap(submitXml(reference, invoice, "700.00", "key-2-" + reference));

        assertThat(retried.tag("PaymentStatus")).isEqualTo("SUCCESS");
        assertThat(retried.tag("ExternalTransactionId")).isEqualTo(failed.tag("ExternalTransactionId"));
        assertThat(recordedFor(invoice)).isEqualTo(1);
    }

    @Test
    void rejectedIsFinal() throws Exception {
        String reference = ref();
        String invoice = invoice();
        scenario(invoice, "REJECTED");

        Reply rejected = soap(submitXml(reference, invoice, "10.00", "key-1-" + reference));
        Reply again = soap(submitXml(reference, invoice, "10.00", "key-2-" + reference));

        assertThat(rejected.tag("PaymentStatus")).isEqualTo("REJECTED");
        assertThat(rejected.tag("FailureReason")).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(again.tag("ErrorCode")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void shortPaySettlesLessThanRequested() throws Exception {
        String invoice = invoice();
        scenario(invoice, "SHORT_PAY");

        Reply reply = soap(submitXml(ref(), invoice, "1000.00", "key-" + invoice));

        assertThat(reply.tag("PaymentStatus")).isEqualTo("SUCCESS");
        assertThat(reply.tag("ProcessedAmount")).isEqualTo("900.0000");
    }

    @Test
    void pendingBecomesSuccessOnTheSecondStatusInquiry() throws Exception {
        String reference = ref();
        String invoice = invoice();
        scenario(invoice, "PENDING_THEN_SUCCESS");

        assertThat(soap(submitXml(reference, invoice, "300.00", "key-" + reference)).tag("PaymentStatus"))
                .isEqualTo("PENDING");
        assertThat(soap(statusXml(reference)).tag("PaymentStatus")).isEqualTo("PENDING");
        Reply settled = soap(statusXml(reference));

        assertThat(settled.tag("PaymentStatus")).isEqualTo("SUCCESS");
        assertThat(settled.tag("ProcessedAmount")).isEqualTo("300.0000");
    }

    @Test
    void anOutageIsAServerFaultAndRecordsNothing() throws Exception {
        String reference = ref();
        String invoice = invoice();
        scenario(invoice, "OUTAGE");

        Reply outage = soap(submitXml(reference, invoice, "55.00", "key-" + reference));

        assertThat(outage.status()).isEqualTo(500);
        assertThat(outage.tag("faultcode")).endsWith("Server");
        assertThat(outage.tag("ErrorCode")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(recordedFor(invoice)).isZero();
        assertThat(soap(statusXml(reference)).tag("ErrorCode")).isEqualTo("UNKNOWN_TRANSACTION");
    }

    @Test
    void aSlowPaymentTimesOutForTheCallerButIsFoundByInquiryAndNeverDuplicated() throws Exception {
        String reference = ref();
        String invoice = invoice();
        scenario(invoice, "SLOW_SUCCESS");
        String xml = submitXml(reference, invoice, "8800.00", "key-" + reference);

        // The caller gives up after 300 ms; the simulator answers after 1500 ms.
        assertThatThrownBy(() -> soap(xml, Duration.ofMillis(300))).isInstanceOf(HttpTimeoutException.class);

        // The payment happened anyway. A status inquiry is how the caller finds out...
        Reply inquiry = soap(statusXml(reference));
        assertThat(inquiry.tag("PaymentStatus")).isEqualTo("SUCCESS");
        // ...and resending the same request is harmless: same result, still one payment.
        Reply resent = soap(xml);
        assertThat(resent.tag("ExternalTransactionId")).isEqualTo(inquiry.tag("ExternalTransactionId"));
        assertThat(recordedFor(invoice)).isEqualTo(1);
    }

    // ---------- faults ----------

    @Test
    void aStatusInquiryForAnUnknownReferenceIsAClientFault() throws Exception {
        Reply reply = soap(statusXml("FB-NEVER-SENT"));

        assertThat(reply.status()).isEqualTo(500);
        assertThat(reply.tag("faultcode")).endsWith("Client");
        assertThat(reply.tag("ErrorCode")).isEqualTo("UNKNOWN_TRANSACTION");
    }

    @Test
    void requestsThatBreakTheSchemaAreRejectedBeforeAnyProcessing() throws Exception {
        String invoice = invoice();

        Reply negative = soap(submitXml(ref(), invoice, "-5.00", "key-neg"));
        Reply zero = soap(submitXml(ref(), invoice, "0", "key-zero"));
        Reply tooPrecise = soap(submitXml(ref(), invoice, "1.00001", "key-precise"));
        Reply badCurrency = soap(submitXml(ref(), invoice, "5.00", "key-cur").replace("INR", "rupees"));
        Reply missingField = soap(submitXml(ref(), invoice, "5.00", "key-miss")
                .replace("<pay:SupplierReference>SUP-42</pay:SupplierReference>", ""));

        for (Reply reply : new Reply[] {negative, zero, tooPrecise, badCurrency, missingField}) {
            assertThat(reply.status()).isEqualTo(500);
            assertThat(reply.tag("faultcode")).endsWith("Client");
            assertThat(reply.tag("ErrorCode")).isEqualTo("INVALID_REQUEST");
        }
        assertThat(recordedFor(invoice)).isZero();
    }

    @Test
    void aDoctypeWithAnExternalEntityIsRefusedAndNothingIsReadOrRecorded() throws Exception {
        String invoice = invoice();
        String attack = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\"><!ENTITY big \"FINBRIDGE-XXE-MARKER\">]>"
                + submitXml(ref(), invoice, "5.00", "key-xxe").replace("SUP-42", "&xxe;&big;");

        Reply reply = soap(attack);

        assertThat(reply.status()).isGreaterThanOrEqualTo(400);
        assertThat(reply.body()).doesNotContain("FINBRIDGE-XXE-MARKER");
        assertThat(reply.tag("PaymentStatus")).isNull();
        assertThat(recordedFor(invoice)).isZero();
    }

    // ---------- statement ----------

    @Test
    void theSettlementStatementListsPaymentsAndOrphansWithTheirSupplier() throws Exception {
        String reference = ref();
        String invoice = invoice();
        soap(submitXml(reference, invoice, "431.25", "key-" + reference));
        Reply orphan = rest("POST", "/sim/orphans", "{\"invoiceReference\":\"" + invoice
                + "\",\"supplierReference\":\"SUP-99\",\"amount\":77.5,\"currency\":\"INR\"}", true);
        assertThat(orphan.status()).isEqualTo(201);

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        Reply statement = soap(envelope("<pay:GetSettlementStatementRequest><pay:FromDate>" + today
                + "</pay:FromDate><pay:ToDate>" + today + "</pay:ToDate></pay:GetSettlementStatementRequest>"));

        assertThat(statement.status()).isEqualTo(200);
        assertThat(statement.count("Entry")).isGreaterThanOrEqualTo(2);
        assertThat(statement.body()).contains(reference, "431.2500", "SUP-99", "77.5000", "EXT-");

        Reply empty = soap(envelope("<pay:GetSettlementStatementRequest><pay:FromDate>2001-01-01</pay:FromDate>"
                + "<pay:ToDate>2001-01-31</pay:ToDate></pay:GetSettlementStatementRequest>"));
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.count("Entry")).isZero();
    }

    // ---------- REST controls ----------

    @Test
    void theRestControlsNeedTheApiKey() throws Exception {
        assertThat(rest("GET", "/sim/health", null, false).status()).isEqualTo(401);
        assertThat(rest("PUT", "/sim/scenarios/BILL/1", "{\"scenario\":\"FAILED\"}", false).status()).isEqualTo(401);
        assertThat(rest("POST", "/sim/orphans", "{}", false).status()).isEqualTo(401);

        Reply health = rest("GET", "/sim/health", null, true);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void anUnknownScenarioNameIsA400() throws Exception {
        Reply reply = rest("PUT", "/sim/scenarios/BILL/2026/11/0001", "{\"scenario\":\"EXPLODE\"}", true);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("INVALID_REQUEST", "SLOW_SUCCESS");
    }
}
