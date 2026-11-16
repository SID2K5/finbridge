package com.finbridge.integration.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withException;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;
import static org.springframework.ws.test.client.ResponseCreators.withSoapEnvelope;

import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogService;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

/**
 * The SOAP adapter against a mock SOAP server: what it puts on the wire, how it reads the
 * answer, and how each kind of failure is translated. No simulator and no database.
 */
class SoapPaymentAdapterTest {

    private static final String NS = "http://finbridge.com/payments/v1";
    private static final PaymentGateway.CallContext CONTEXT = new PaymentGateway.CallContext(7L, 99L);
    private static final PaymentGateway.Instruction INSTRUCTION = new PaymentGateway.Instruction(
            "FB-20261110-7K2M9QXA", "BILL/2026/11/0001", "SUP-42", new BigDecimal("50000.0000"), "INR",
            "3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11");

    private final IntegrationLogService integrationLog = mock(IntegrationLogService.class);
    private MockWebServiceServer server;
    private SoapPaymentAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        PaymentProperties properties = new PaymentProperties("http://simulator.test:8090/ws", Duration.ofSeconds(3),
                Duration.ofSeconds(10), 3, "http://simulator.test:8090", "k");
        SoapClientConfig config = new SoapClientConfig();
        Jaxb2Marshaller marshaller = config.paymentsMarshaller();
        marshaller.afterPropertiesSet();
        SoapExchangeRecorder recorder = config.soapExchangeRecorder();
        WebServiceTemplate template = config.paymentsWebServiceTemplate(properties, marshaller, recorder);
        server = MockWebServiceServer.createServer(template);
        adapter = new SoapPaymentAdapter(template, marshaller, recorder, integrationLog, properties,
                Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneOffset.UTC));
    }

    private static String result(String element, String status, String processed, String extra) {
        return "<pay:" + element + " xmlns:pay=\"" + NS + "\">"
                + "<pay:ExternalTransactionId>SIM-0001</pay:ExternalTransactionId>"
                + "<pay:TransactionReference>FB-20261110-7K2M9QXA</pay:TransactionReference>"
                + "<pay:InvoiceReference>BILL/2026/11/0001</pay:InvoiceReference>"
                + "<pay:ProcessedAmount>" + processed + "</pay:ProcessedAmount>"
                + "<pay:Currency>INR</pay:Currency>"
                + "<pay:PaymentStatus>" + status + "</pay:PaymentStatus>"
                + "<pay:ResponseTimestamp>2026-11-10T09:30:01.250Z</pay:ResponseTimestamp>"
                + extra + "</pay:" + element + ">";
    }

    private static String fault(String side, String errorCode) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + "<soapenv:Fault><faultcode>soapenv:" + side + "</faultcode><faultstring>refused</faultstring>"
                + "<detail><pay:PaymentFault xmlns:pay=\"" + NS + "\"><pay:ErrorCode>" + errorCode
                + "</pay:ErrorCode><pay:Message>Explained by the service</pay:Message></pay:PaymentFault></detail>"
                + "</soapenv:Fault></soapenv:Body></soapenv:Envelope>";
    }

    private IntegrationLogEntry logged() {
        ArgumentCaptor<IntegrationLogEntry> captor = ArgumentCaptor.forClass(IntegrationLogEntry.class);
        verify(integrationLog).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitSendsTheContractRequestAndReadsTheResult() {
        server.expect(payload(new StringSource("<pay:SubmitPaymentRequest xmlns:pay=\"" + NS + "\">"
                        + "<pay:TransactionReference>FB-20261110-7K2M9QXA</pay:TransactionReference>"
                        + "<pay:InvoiceReference>BILL/2026/11/0001</pay:InvoiceReference>"
                        + "<pay:SupplierReference>SUP-42</pay:SupplierReference>"
                        + "<pay:RequestedAmount>50000.0000</pay:RequestedAmount>"
                        + "<pay:Currency>INR</pay:Currency>"
                        + "<pay:RequestTimestamp>2026-11-10T09:30:00Z</pay:RequestTimestamp>"
                        + "<pay:IdempotencyKey>3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11</pay:IdempotencyKey>"
                        + "</pay:SubmitPaymentRequest>")))
                .andRespond(withPayload(new StringSource(result("SubmitPaymentResponse", "SUCCESS", "50000.0000", ""))));

        PaymentGateway.Outcome outcome = adapter.submit(INSTRUCTION, CONTEXT);

        server.verify();
        assertThat(outcome.status()).isEqualTo(PaymentGateway.Status.SUCCESS);
        assertThat(outcome.externalTransactionId()).isEqualTo("SIM-0001");
        assertThat(outcome.processedAmount()).isEqualByComparingTo("50000");
        assertThat(outcome.responseTimestamp()).isEqualTo(Instant.parse("2026-11-10T09:30:01.250Z"));
        assertThat(outcome.failureReason()).isNull();

        IntegrationLogEntry entry = logged();
        assertThat(entry.type()).isEqualTo(IntegrationLogEntry.Type.SOAP);
        assertThat(entry.operation()).isEqualTo("SubmitPayment");
        assertThat(entry.outcome()).isEqualTo(IntegrationLogEntry.Outcome.SUCCESS);
        assertThat(entry.requestReference()).isEqualTo("FB-20261110-7K2M9QXA");
        assertThat(entry.invoiceId()).isEqualTo(7L);
        assertThat(entry.paymentTransactionId()).isEqualTo(99L);
        assertThat(entry.requestPayload()).contains("Envelope", "SubmitPaymentRequest", "50000.0000");
        assertThat(entry.responsePayload()).contains("SubmitPaymentResponse", "SIM-0001");
    }

    @Test
    void aBusinessFailureIsANormalOutcomeNotAnException() {
        server.expect(payload(new StringSource("<pay:GetPaymentStatusRequest xmlns:pay=\"" + NS
                        + "\"><pay:TransactionReference>FB-20261110-7K2M9QXA</pay:TransactionReference>"
                        + "</pay:GetPaymentStatusRequest>")))
                .andRespond(withPayload(new StringSource(result("GetPaymentStatusResponse", "FAILED", "0.0000",
                        "<pay:FailureReason>PROCESSING_ERROR</pay:FailureReason>"))));

        PaymentGateway.Outcome outcome = adapter.inquire("FB-20261110-7K2M9QXA", CONTEXT);

        assertThat(outcome.status()).isEqualTo(PaymentGateway.Status.FAILED);
        assertThat(outcome.failureReason()).isEqualTo("PROCESSING_ERROR");
        assertThat(logged().operation()).isEqualTo("GetPaymentStatus");
    }

    @Test
    void aFaultCarriesTheServicesErrorCode() {
        server.expect(org.springframework.ws.test.client.RequestMatchers.anything())
                .andRespond(withSoapEnvelope(new StringSource(fault("Client", "UNKNOWN_TRANSACTION"))));

        assertThatThrownBy(() -> adapter.inquire("FB-NEVER-SENT", CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.FAULT);
                    assertThat(ex.getErrorCode()).isEqualTo("UNKNOWN_TRANSACTION");
                    assertThat(ex.isFault(PaymentGatewayException.UNKNOWN_TRANSACTION)).isTrue();
                    assertThat(ex.getMessage()).isEqualTo("Explained by the service");
                });

        IntegrationLogEntry entry = logged();
        assertThat(entry.outcome()).isEqualTo(IntegrationLogEntry.Outcome.FAILURE);
        assertThat(entry.errorCode()).isEqualTo("UNKNOWN_TRANSACTION");
        assertThat(entry.retryable()).isFalse();
        assertThat(entry.responsePayload()).contains("Fault", "UNKNOWN_TRANSACTION");
    }

    @Test
    void anOutageFaultIsMarkedRetryable() {
        server.expect(org.springframework.ws.test.client.RequestMatchers.anything())
                .andRespond(withSoapEnvelope(new StringSource(fault("Server", "SERVICE_UNAVAILABLE"))));

        assertThatThrownBy(() -> adapter.submit(INSTRUCTION, CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThat(logged().retryable()).isTrue();
    }

    @Test
    void aReadTimeoutMeansTheOutcomeIsUnknown() {
        server.expect(org.springframework.ws.test.client.RequestMatchers.anything())
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.submit(INSTRUCTION, CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.TIMEOUT);
                    assertThat(ex.getMessage()).contains("may or may not have been processed");
                });

        IntegrationLogEntry entry = logged();
        assertThat(entry.outcome()).isEqualTo(IntegrationLogEntry.Outcome.TIMEOUT);
        assertThat(entry.errorCode()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(entry.requestPayload()).contains("SubmitPaymentRequest");   // captured before sending
        assertThat(entry.responsePayload()).isNull();
    }

    @Test
    void aRefusedConnectionMeansTheRequestWasNeverSent() {
        server.expect(org.springframework.ws.test.client.RequestMatchers.anything())
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> adapter.submit(INSTRUCTION, CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.UNREACHABLE));
        assertThat(logged().outcome()).isEqualTo(IntegrationLogEntry.Outcome.FAILURE);
    }

    @Test
    void aResponseThatBreaksTheSchemaIsRefused() {
        server.expect(org.springframework.ws.test.client.RequestMatchers.anything())
                .andRespond(withPayload(new StringSource(result("SubmitPaymentResponse", "MAYBE", "-1", ""))));

        assertThatThrownBy(() -> adapter.submit(INSTRUCTION, CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.BAD_RESPONSE));
    }

    @Test
    void aRequestThatBreaksTheSchemaNeverLeaves() {
        PaymentGateway.Instruction invalid = new PaymentGateway.Instruction("FB-1", "BILL/1", "SUP-1",
                new BigDecimal("10.0000"), "rupees", "key-12345678");

        java.util.concurrent.atomic.AtomicBoolean reachedTheServer = new java.util.concurrent.atomic.AtomicBoolean();
        server.expect((uri, request) -> reachedTheServer.set(true))
                .andRespond(withPayload(new StringSource(result("SubmitPaymentResponse", "SUCCESS", "10.0000", ""))));

        assertThatThrownBy(() -> adapter.submit(invalid, CONTEXT))
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
                    assertThat(ex.isFault(PaymentGatewayException.INVALID_REQUEST)).isTrue();
                    assertThat(ex.getMessage()).contains("not sent");
                });
        // The mock server was ready to answer, and the request never reached it.
        assertThat(reachedTheServer).isFalse();
    }

    @Test
    void theStatementIsReadEntryByEntry() {
        server.expect(payload(new StringSource("<pay:GetSettlementStatementRequest xmlns:pay=\"" + NS
                        + "\"><pay:FromDate>2026-11-01</pay:FromDate><pay:ToDate>2026-11-30</pay:ToDate>"
                        + "</pay:GetSettlementStatementRequest>")))
                .andRespond(withPayload(new StringSource("<pay:GetSettlementStatementResponse xmlns:pay=\"" + NS
                        + "\">" + result("Entry", "SUCCESS", "450.0000",
                        "<pay:SupplierReference>SUP-42</pay:SupplierReference>")
                        + "</pay:GetSettlementStatementResponse>")));

        List<PaymentGateway.StatementEntry> entries =
                adapter.statement(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        server.verify();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).supplierReference()).isEqualTo("SUP-42");
        assertThat(entries.get(0).outcome().processedAmount()).isEqualByComparingTo("450");
    }
}
