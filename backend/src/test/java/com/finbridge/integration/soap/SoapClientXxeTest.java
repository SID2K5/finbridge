package com.finbridge.integration.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.finbridge.logging.IntegrationLogService;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;

/**
 * XXE from the other direction: a hostile or compromised payment service answers with XML
 * that tries to make FinBridge read a local file or expand entities. A real HTTP server
 * sends the raw bytes, so nothing between the socket and the parser is mocked.
 */
class SoapClientXxeTest {

    private static final String NS = "http://finbridge.com/payments/v1";
    private static final String MARKER = "FINBRIDGE-SECRET-FILE-CONTENT";

    private HttpServer server;
    private SoapPaymentAdapter adapter;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String responseXml;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseXml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ws";
        PaymentProperties properties = new PaymentProperties(url, Duration.ofSeconds(3), Duration.ofSeconds(5), 3,
                url, "k");
        SoapClientConfig config = new SoapClientConfig();
        Jaxb2Marshaller marshaller = config.paymentsMarshaller();
        marshaller.afterPropertiesSet();
        SoapExchangeRecorder recorder = config.soapExchangeRecorder();
        WebServiceTemplate template = config.paymentsWebServiceTemplate(properties, marshaller, recorder);
        adapter = new SoapPaymentAdapter(template, marshaller, recorder, mock(IntegrationLogService.class),
                properties, Clock.systemUTC());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String response(String prolog, String failureReason) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + prolog
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + "<pay:SubmitPaymentResponse xmlns:pay=\"" + NS + "\">"
                + "<pay:ExternalTransactionId>SIM-1</pay:ExternalTransactionId>"
                + "<pay:TransactionReference>FB-1</pay:TransactionReference>"
                + "<pay:InvoiceReference>BILL/1</pay:InvoiceReference>"
                + "<pay:ProcessedAmount>10.0000</pay:ProcessedAmount><pay:Currency>INR</pay:Currency>"
                + "<pay:PaymentStatus>FAILED</pay:PaymentStatus>"
                + "<pay:ResponseTimestamp>2026-11-10T09:30:01Z</pay:ResponseTimestamp>"
                + "<pay:FailureReason>" + failureReason + "</pay:FailureReason>"
                + "</pay:SubmitPaymentResponse></soapenv:Body></soapenv:Envelope>";
    }

    private PaymentGateway.Outcome submit() {
        return adapter.submit(new PaymentGateway.Instruction("FB-1", "BILL/1", "SUP-1", new BigDecimal("10.0000"),
                "INR", "key-12345678"), PaymentGateway.CallContext.NONE);
    }

    @Test
    void aWellFormedResponseFromTheSameServerIsReadNormally() {
        responseXml = response("", "PROCESSING_ERROR");

        PaymentGateway.Outcome outcome = submit();

        assertThat(outcome.failureReason()).isEqualTo("PROCESSING_ERROR");
        assertThat(requests).hasValue(1);
    }

    @Test
    void anExternalEntityInTheResponseNeverReadsALocalFile(@TempDir Path directory) throws Exception {
        Path secret = Files.writeString(directory.resolve("secret.txt"), MARKER);
        responseXml = response("<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>", "&xxe;");

        assertThatThrownBy(this::submit)
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
                    // The answer was sent, so the payment's outcome is unknown, not "failed".
                    assertThat(ex.getKind()).isIn(PaymentGatewayException.Kind.BAD_RESPONSE,
                            PaymentGatewayException.Kind.TIMEOUT);
                    assertThat(String.valueOf(ex.getMessage())).doesNotContain(MARKER);
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void anInternalEntityInTheResponseIsNotExpandedIntoTheResult() {
        responseXml = response("<!DOCTYPE foo [<!ENTITY big \"" + MARKER + "\">]>", "&big;");

        try {
            PaymentGateway.Outcome outcome = submit();
            // If a parser ever accepts the DOCTYPE, the entity must at least not reach FinBridge's data.
            assertThat(outcome.failureReason()).doesNotContain(MARKER);
        } catch (PaymentGatewayException refused) {
            assertThat(String.valueOf(refused.getMessage())).doesNotContain(MARKER);
        }
    }
}
