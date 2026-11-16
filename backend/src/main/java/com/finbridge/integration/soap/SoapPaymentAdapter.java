package com.finbridge.integration.soap;

import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogEntry.Type;
import com.finbridge.logging.IntegrationLogService;
import com.finbridge.payments.v1.GetPaymentStatusRequest;
import com.finbridge.payments.v1.GetSettlementStatementRequest;
import com.finbridge.payments.v1.GetSettlementStatementResponse;
import com.finbridge.payments.v1.PaymentFault;
import com.finbridge.payments.v1.PaymentResultType;
import com.finbridge.payments.v1.SubmitPaymentRequest;
import jakarta.xml.bind.JAXBElement;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.SoapFaultDetailElement;
import org.springframework.ws.soap.client.SoapFaultClientException;

/**
 * The SOAP adapter for the payment service (SOAP 1.1, document/literal, contract in
 * wsdl/payments.xsd). It does three jobs and nothing else:
 *
 *   1. converts FinBridge records to and from the generated JAXB types;
 *   2. translates every failure into a PaymentGatewayException that says whether the
 *      request may have been processed (TIMEOUT, BAD_RESPONSE) or certainly was not
 *      (UNREACHABLE, FAULT);
 *   3. writes one integration_logs row per call, with the request and response XML.
 *
 * It never retries by itself. Whether and how to try again is a business decision that
 * belongs to the payment module.
 */
@Component
public class SoapPaymentAdapter implements PaymentGateway {

    private static final DatatypeFactory DATATYPES;

    static {
        try {
            DATATYPES = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final WebServiceTemplate template;
    private final Jaxb2Marshaller marshaller;
    private final SoapExchangeRecorder recorder;
    private final IntegrationLogService integrationLog;
    private final PaymentProperties properties;
    private final Clock clock;

    public SoapPaymentAdapter(WebServiceTemplate paymentsWebServiceTemplate, Jaxb2Marshaller paymentsMarshaller,
                              SoapExchangeRecorder recorder, IntegrationLogService integrationLog,
                              PaymentProperties properties, Clock clock) {
        this.template = paymentsWebServiceTemplate;
        this.marshaller = paymentsMarshaller;
        this.recorder = recorder;
        this.integrationLog = integrationLog;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public PaymentGateway.Outcome submit(Instruction instruction, CallContext context) {
        SubmitPaymentRequest request = new SubmitPaymentRequest();
        request.setTransactionReference(instruction.transactionReference());
        request.setInvoiceReference(instruction.invoiceReference());
        request.setSupplierReference(instruction.supplierReference());
        request.setRequestedAmount(instruction.amount().setScale(4, RoundingMode.UNNECESSARY));
        request.setCurrency(instruction.currency());
        request.setRequestTimestamp(xmlTimestamp(clock.instant()));
        request.setIdempotencyKey(instruction.idempotencyKey());
        PaymentResultType result = call("SubmitPayment", instruction.transactionReference(), context,
                () -> unwrap(template.marshalSendAndReceive(request)));
        return outcome(result);
    }

    @Override
    public PaymentGateway.Outcome inquire(String transactionReference, CallContext context) {
        GetPaymentStatusRequest request = new GetPaymentStatusRequest();
        request.setTransactionReference(transactionReference);
        PaymentResultType result = call("GetPaymentStatus", transactionReference, context,
                () -> unwrap(template.marshalSendAndReceive(request)));
        return outcome(result);
    }

    @Override
    public List<StatementEntry> statement(LocalDate from, LocalDate to) {
        GetSettlementStatementRequest request = new GetSettlementStatementRequest();
        request.setFromDate(DATATYPES.newXMLGregorianCalendar(from.toString()));
        request.setToDate(DATATYPES.newXMLGregorianCalendar(to.toString()));
        GetSettlementStatementResponse response = call("GetSettlementStatement", from + ".." + to,
                CallContext.NONE, () -> (GetSettlementStatementResponse) template.marshalSendAndReceive(request));
        return response.getEntry().stream()
                .map(entry -> new StatementEntry(outcome(entry), entry.getSupplierReference())).toList();
    }

    @Override
    public void ping() {
        String url = properties.wsUrl().replaceAll("/+$", "") + "/payments.wsdl";
        long started = System.nanoTime();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(properties.readTimeout());
            String wsdl = RestClient.builder().requestFactory(factory).build().get().uri(url).retrieve()
                    .body(String.class);
            if (wsdl == null || !wsdl.contains("PaymentsPort")) {
                throw new PaymentGatewayException(PaymentGatewayException.Kind.BAD_RESPONSE, null,
                        "The payment service answered, but not with the payments WSDL", null);
            }
            integrationLog.record(IntegrationLogEntry.success(Type.SOAP, "GET payments.wsdl", null, 200,
                    elapsedMs(started), null, "WSDL with port type PaymentsPort, " + wsdl.length() + " characters"));
        } catch (RestClientException ex) {
            PaymentGatewayException failure = new PaymentGatewayException(PaymentGatewayException.Kind.UNREACHABLE,
                    null, "The payment service could not be reached at " + properties.wsUrl(), ex);
            integrationLog.record(IntegrationLogEntry.failure(Type.SOAP, "GET payments.wsdl", null, IntegrationLogEntry.Outcome.FAILURE,
                    null, elapsedMs(started), failure.getErrorCode(), failure.getMessage(), null, null, true));
            throw failure;
        } catch (PaymentGatewayException ex) {
            integrationLog.record(IntegrationLogEntry.failure(Type.SOAP, "GET payments.wsdl", null, IntegrationLogEntry.Outcome.FAILURE,
                    200, elapsedMs(started), ex.getErrorCode(), ex.getMessage(), null, null, false));
            throw ex;
        }
    }

    /** Runs one SOAP exchange, logs it whatever happens, and translates failures. */
    private <T> T call(String operation, String reference, CallContext context, Supplier<T> exchange) {
        long started = System.nanoTime();
        recorder.begin();
        try {
            T result = exchange.get();
            SoapExchangeRecorder.Exchange xml = recorder.end();
            integrationLog.record(IntegrationLogEntry.success(Type.SOAP, operation, reference, 200,
                    elapsedMs(started), xml.requestXml(), xml.responseXml())
                    .forRecords(context.invoiceId(), context.paymentTransactionId()));
            return result;
        } catch (RuntimeException ex) {
            SoapExchangeRecorder.Exchange xml = recorder.end();
            PaymentGatewayException failure = translate(ex);
            boolean timeout = failure.getKind() == PaymentGatewayException.Kind.TIMEOUT;
            boolean retryable = failure.getKind() != PaymentGatewayException.Kind.FAULT
                    || failure.isFault(PaymentGatewayException.SERVICE_UNAVAILABLE);
            integrationLog.record(IntegrationLogEntry.failure(Type.SOAP, operation, reference,
                    timeout ? IntegrationLogEntry.Outcome.TIMEOUT : IntegrationLogEntry.Outcome.FAILURE,
                    failure.getKind() == PaymentGatewayException.Kind.FAULT ? 500 : null, elapsedMs(started),
                    failure.getErrorCode(), failure.getMessage(), xml.requestXml(), xml.responseXml(), retryable)
                    .forRecords(context.invoiceId(), context.paymentTransactionId()));
            throw failure;
        }
    }

    private PaymentGatewayException translate(RuntimeException ex) {
        if (ex instanceof PaymentGatewayException already) {
            return already;
        }
        if (ex instanceof SoapFaultClientException fault) {
            PaymentFault detail = faultDetail(fault);
            String code = detail != null ? detail.getErrorCode() : null;
            String message = detail != null && detail.getMessage() != null ? detail.getMessage()
                    : fault.getFaultStringOrReason();
            return new PaymentGatewayException(PaymentGatewayException.Kind.FAULT,
                    code != null ? code : "UNCLASSIFIED_FAULT", message, ex);
        }
        if (ex instanceof ContractValidatingInterceptor.InvalidRequestException) {
            // Stopped before sending: certain not to have been processed. Reported like the
            // service's own INVALID_REQUEST fault, because the meaning is the same.
            return new PaymentGatewayException(PaymentGatewayException.Kind.FAULT,
                    PaymentGatewayException.INVALID_REQUEST, ex.getMessage() + " (not sent)", ex);
        }
        if (ex instanceof WebServiceIOException) {
            if (neverSent(ex)) {
                return new PaymentGatewayException(PaymentGatewayException.Kind.UNREACHABLE, null,
                        "The payment service could not be reached at " + properties.wsUrl()
                                + "; the request was not sent", ex);
            }
            return new PaymentGatewayException(PaymentGatewayException.Kind.TIMEOUT, null,
                    "The payment service did not answer within " + properties.readTimeout().toMillis()
                            + " ms. The request was sent, so the payment may or may not have been processed", ex);
        }
        // Sent and answered, but unreadable or not valid against the contract.
        return new PaymentGatewayException(PaymentGatewayException.Kind.BAD_RESPONSE, null,
                "The payment service's answer could not be read against the contract", ex);
    }

    /** True only when the failure proves the connection was never established. */
    private static boolean neverSent(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            if (cause instanceof SocketTimeoutException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase().contains("connect")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private PaymentFault faultDetail(SoapFaultClientException fault) {
        try {
            SoapFaultDetail detail = fault.getSoapFault() != null ? fault.getSoapFault().getFaultDetail() : null;
            if (detail == null) {
                return null;
            }
            Iterator<SoapFaultDetailElement> entries = detail.getDetailEntries();
            while (entries.hasNext()) {
                Object value = marshaller.unmarshal(entries.next().getSource());
                if (value instanceof PaymentFault paymentFault) {
                    return paymentFault;
                }
            }
        } catch (RuntimeException ignored) {
            // a fault without the contract's detail element: reported as unclassified
        }
        return null;
    }

    private static PaymentResultType unwrap(Object response) {
        Object value = response instanceof JAXBElement<?> element ? element.getValue() : response;
        if (value instanceof PaymentResultType result) {
            return result;
        }
        throw new IllegalStateException("Unexpected response type " + (value == null ? "null" : value.getClass()));
    }

    private static PaymentGateway.Outcome outcome(PaymentResultType result) {
        return new PaymentGateway.Outcome(result.getExternalTransactionId(), result.getTransactionReference(),
                result.getInvoiceReference(), result.getProcessedAmount(), result.getCurrency(),
                Status.valueOf(result.getPaymentStatus().value()),
                result.getResponseTimestamp().toGregorianCalendar().toInstant(), result.getFailureReason());
    }

    private static XMLGregorianCalendar xmlTimestamp(Instant instant) {
        return DATATYPES.newXMLGregorianCalendar(instant.truncatedTo(ChronoUnit.MILLIS).toString());
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
