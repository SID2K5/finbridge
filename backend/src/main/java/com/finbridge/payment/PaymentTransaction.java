package com.finbridge.payment;

import com.finbridge.common.AuditedEntity;
import com.finbridge.invoice.Invoice;
import com.finbridge.supplier.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One payment of one invoice. The row is created before anything is sent, so there is
 * always a record of the intention, whatever happens on the network afterwards.
 *
 * Two unique keys protect against paying twice: idempotency_key (the caller's key) and
 * transaction_reference (FinBridge's own reference, also sent to the payment service).
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true, length = 40, updatable = false)
    private String transactionReference;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal requestedAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 24)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "external_transaction_id", unique = true, length = 64)
    private String externalTransactionId;

    @Column(name = "processed_amount", precision = 19, scale = 4)
    private BigDecimal processedAmount;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private Long initiatedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(String transactionReference, String idempotencyKey, String requestHash,
                              Invoice invoice, Long initiatedBy, Instant requestedAt) {
        this.transactionReference = transactionReference;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.invoice = invoice;
        this.supplier = invoice.getSupplier();
        this.requestedAmount = invoice.getTotalAmount();
        this.currency = invoice.getCurrency();
        this.initiatedBy = initiatedBy;
        this.requestedAt = requestedAt;
    }

    /** A request is about to leave. newAttempt is false when the same attempt is sent again after a lost answer. */
    public void markSent(boolean newAttempt, Instant now) {
        if (newAttempt) {
            this.attemptCount++;
        }
        this.status = PaymentStatus.SENT;
        this.lastAttemptAt = now;
        this.failureReason = null;
    }

    /** The payment service answered with a business outcome. */
    public void applyOutcome(PaymentStatus status, String externalTransactionId, BigDecimal processedAmount,
                             String failureReason, Instant now) {
        this.status = status;
        this.externalTransactionId = externalTransactionId;
        this.processedAmount = processedAmount;
        this.failureReason = limit(failureReason);
        this.respondedAt = now;
    }

    public void markTimeoutUnknown(String reason) {
        this.status = PaymentStatus.TIMEOUT_UNKNOWN;
        this.failureReason = limit(reason);
    }

    /** Not paid, and nothing usable came back (a fault, or the service could not be reached). */
    public void markFailed(String reason, Instant now) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = limit(reason);
        this.respondedAt = now;
    }

    private static String limit(String text) {
        return text == null || text.length() <= 255 ? text : text.substring(0, 255);
    }

    public Long getId() {
        return id;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getExternalTransactionId() {
        return externalTransactionId;
    }

    public BigDecimal getProcessedAmount() {
        return processedAmount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Long getVersion() {
        return version;
    }
}
