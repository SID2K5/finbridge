package com.finbridge.integration.soap;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The port between FinBridge and the external payment service. Only the adapter behind it
 * knows SOAP, JAXB and fault codes; the payment and reconciliation modules see plain records.
 */
public interface PaymentGateway {

    enum Status { SUCCESS, PENDING, FAILED, REJECTED }

    /** One payment instruction. idempotencyKey is what makes sending it twice harmless. */
    record Instruction(String transactionReference, String invoiceReference, String supplierReference,
                       BigDecimal amount, String currency, String idempotencyKey) {
    }

    /** What the payment service says happened to a payment. */
    record Outcome(String externalTransactionId, String transactionReference, String invoiceReference,
                   BigDecimal processedAmount, String currency, Status status, Instant responseTimestamp,
                   String failureReason) {
    }

    /** One line of the settlement statement. */
    record StatementEntry(Outcome outcome, String supplierReference) {
    }

    /** Which FinBridge records a call belongs to, so its integration log row can point at them. */
    record CallContext(Long invoiceId, Long paymentTransactionId) {

        public static final CallContext NONE = new CallContext(null, null);
    }

    Outcome submit(Instruction instruction, CallContext context);

    Outcome inquire(String transactionReference, CallContext context);

    List<StatementEntry> statement(LocalDate from, LocalDate to);

    /** Fetches the WSDL: proves the service is reachable without touching any payment. */
    void ping();
}
