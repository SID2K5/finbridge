package com.finbridge.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One finding of one run: an internal payment, an external statement entry, or both, and
 * how they compare. Reconciliation only ever writes here and to reconciliation_runs; a
 * review changes review_status and never an invoice or a payment.
 */
@Entity
@Table(name = "reconciliation_records")
public class ReconciliationRecord {

    public enum Status {
        MATCHED, MISMATCH, MISSING_EXTERNAL_RECORD, MISSING_INTERNAL_RECORD, DUPLICATE, PENDING_REVIEW
    }

    public enum ReviewStatus { NOT_REQUIRED, OPEN, RESOLVED, NEEDS_INVESTIGATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reconciliation_run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "invoice_id", updatable = false)
    private Long invoiceId;

    @Column(name = "payment_transaction_id", updatable = false)
    private Long paymentTransactionId;

    @Column(name = "internal_transaction_reference", length = 40, updatable = false)
    private String internalTransactionReference;

    @Column(name = "external_transaction_reference", length = 64, updatable = false)
    private String externalTransactionReference;

    @Column(name = "expected_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal actualAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32, updatable = false)
    private Status status;

    @Column(name = "discrepancy_description", length = 1000, updatable = false)
    private String discrepancyDescription;

    @Column(name = "internal_snapshot", columnDefinition = "TEXT", updatable = false)
    private String internalSnapshot;

    @Column(name = "external_snapshot", columnDefinition = "TEXT", updatable = false)
    private String externalSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "review_status", nullable = false, length = 24)
    private ReviewStatus reviewStatus;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reconciled_at", nullable = false, updatable = false)
    private Instant reconciledAt;

    protected ReconciliationRecord() {
    }

    public ReconciliationRecord(Long runId, ReconciliationEngine.Finding finding, String internalSnapshot,
                                String externalSnapshot, Instant reconciledAt) {
        this.runId = runId;
        this.invoiceId = finding.invoiceId();
        this.paymentTransactionId = finding.internal() != null ? finding.internal().id() : null;
        this.internalTransactionReference = finding.internal() != null
                ? finding.internal().transactionReference() : null;
        this.externalTransactionReference = finding.external() != null
                ? finding.external().externalTransactionId() : null;
        this.expectedAmount = finding.expectedAmount();
        this.actualAmount = finding.actualAmount();
        this.currency = finding.currency();
        this.status = finding.status();
        String description = finding.description();
        this.discrepancyDescription = description != null && description.length() > 1000
                ? description.substring(0, 1000) : description;
        this.internalSnapshot = internalSnapshot;
        this.externalSnapshot = externalSnapshot;
        // Only a clean match needs no human eye. Everything else waits for a review.
        this.reviewStatus = finding.status() == Status.MATCHED ? ReviewStatus.NOT_REQUIRED : ReviewStatus.OPEN;
        this.reconciledAt = reconciledAt;
    }

    public void review(ReviewStatus decision, String note, Long reviewerId, Instant now) {
        this.reviewStatus = decision;
        this.reviewNote = note;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public Long getPaymentTransactionId() {
        return paymentTransactionId;
    }

    public String getInternalTransactionReference() {
        return internalTransactionReference;
    }

    public String getExternalTransactionReference() {
        return externalTransactionReference;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public BigDecimal getActualAmount() {
        return actualAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getDiscrepancyDescription() {
        return discrepancyDescription;
    }

    public String getInternalSnapshot() {
        return internalSnapshot;
    }

    public String getExternalSnapshot() {
        return externalSnapshot;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }
}
