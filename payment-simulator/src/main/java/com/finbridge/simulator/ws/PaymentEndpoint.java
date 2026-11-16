package com.finbridge.simulator.ws;

import com.finbridge.payments.v1.GetPaymentStatusRequest;
import com.finbridge.payments.v1.GetSettlementStatementRequest;
import com.finbridge.payments.v1.GetSettlementStatementResponse;
import com.finbridge.payments.v1.ObjectFactory;
import com.finbridge.payments.v1.PaymentResultType;
import com.finbridge.payments.v1.PaymentStatusType;
import com.finbridge.payments.v1.StatementEntryType;
import com.finbridge.payments.v1.SubmitPaymentRequest;
import com.finbridge.simulator.domain.PaymentProcessor;
import com.finbridge.simulator.domain.SimPayment;
import jakarta.xml.bind.JAXBElement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/** The three operations of port type PaymentsPort. Requests arrive here already schema-valid. */
@Endpoint
public class PaymentEndpoint {

    private static final Logger log = LoggerFactory.getLogger(PaymentEndpoint.class);
    private static final ObjectFactory FACTORY = new ObjectFactory();
    private static final DatatypeFactory DATATYPES;

    static {
        try {
            DATATYPES = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final PaymentProcessor processor;

    public PaymentEndpoint(PaymentProcessor processor) {
        this.processor = processor;
    }

    @PayloadRoot(namespace = WsConfig.NAMESPACE, localPart = "SubmitPaymentRequest")
    @ResponsePayload
    public JAXBElement<PaymentResultType> submit(@RequestPayload SubmitPaymentRequest request) {
        PaymentProcessor.Submission submission = processor.submit(request.getTransactionReference(),
                request.getInvoiceReference(), request.getSupplierReference(), request.getRequestedAmount(),
                request.getCurrency(), request.getIdempotencyKey());
        SimPayment payment = submission.payment();
        log.info("submit {} invoice {} -> {} ({})", payment.getTransactionReference(),
                payment.getInvoiceReference(), payment.getStatus(), payment.getScenario());
        // The payment is already committed. SLOW_SUCCESS now keeps the caller waiting past its
        // timeout: the money has moved, but the caller does not know it.
        pause(submission.delayBeforeAnswering());
        return FACTORY.createSubmitPaymentResponse(fill(new PaymentResultType(), payment));
    }

    @PayloadRoot(namespace = WsConfig.NAMESPACE, localPart = "GetPaymentStatusRequest")
    @ResponsePayload
    public JAXBElement<PaymentResultType> status(@RequestPayload GetPaymentStatusRequest request) {
        SimPayment payment = processor.inquire(request.getTransactionReference());
        log.info("status {} -> {}", payment.getTransactionReference(), payment.getStatus());
        return FACTORY.createGetPaymentStatusResponse(fill(new PaymentResultType(), payment));
    }

    @PayloadRoot(namespace = WsConfig.NAMESPACE, localPart = "GetSettlementStatementRequest")
    @ResponsePayload
    public GetSettlementStatementResponse statement(@RequestPayload GetSettlementStatementRequest request) {
        LocalDate from = LocalDate.of(request.getFromDate().getYear(), request.getFromDate().getMonth(),
                request.getFromDate().getDay());
        LocalDate to = LocalDate.of(request.getToDate().getYear(), request.getToDate().getMonth(),
                request.getToDate().getDay());
        GetSettlementStatementResponse response = new GetSettlementStatementResponse();
        for (SimPayment payment : processor.statement(from, to)) {
            StatementEntryType entry = fill(new StatementEntryType(), payment);
            entry.setSupplierReference(payment.getSupplierReference());
            response.getEntry().add(entry);
        }
        log.info("statement {} to {} -> {} entries", from, to, response.getEntry().size());
        return response;
    }

    private static <T extends PaymentResultType> T fill(T result, SimPayment payment) {
        result.setExternalTransactionId(payment.getExternalTransactionId());
        result.setTransactionReference(payment.getTransactionReference());
        result.setInvoiceReference(payment.getInvoiceReference());
        result.setProcessedAmount(payment.getProcessedAmount());
        result.setCurrency(payment.getCurrency());
        result.setPaymentStatus(PaymentStatusType.fromValue(payment.getStatus()));
        result.setResponseTimestamp(timestamp(payment.getResponseTimestamp()));
        result.setFailureReason(payment.getFailureReason());
        return result;
    }

    private static XMLGregorianCalendar timestamp(Instant instant) {
        return DATATYPES.newXMLGregorianCalendar(instant.truncatedTo(ChronoUnit.MILLIS).toString());
    }

    private static void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
