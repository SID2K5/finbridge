package com.finbridge.logging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One outbound call to an external system (Odoo, the SOAP payment service, a REST service).
 * Rows are written once and never updated. Payloads are masked and size-capped before they
 * reach this entity (see PayloadMasker).
 */
@Entity
@Table(name = "integration_logs")
public class IntegrationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "correlation_id", nullable = false, length = 36, updatable = false)
    private String correlationId;

    @Column(name = "integration_type", nullable = false, length = 8, updatable = false)
    private String integrationType;

    @Column(nullable = false, length = 64, updatable = false)
    private String operation;

    @Column(name = "request_reference", length = 64, updatable = false)
    private String requestReference;

    @Column(nullable = false, length = 16, updatable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private int durationMs;

    @Column(name = "error_code", length = 64, updatable = false)
    private String errorCode;

    @Column(name = "error_description", length = 1000, updatable = false)
    private String errorDescription;

    @Column(name = "invoice_id", updatable = false)
    private Long invoiceId;

    @Column(name = "payment_transaction_id", updatable = false)
    private Long paymentTransactionId;

    @Column(name = "request_payload", columnDefinition = "MEDIUMTEXT", updatable = false)
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "MEDIUMTEXT", updatable = false)
    private String responsePayload;

    @Column(nullable = false, updatable = false)
    private boolean retryable;

    @Column(name = "retry_of_log_id", updatable = false)
    private Long retryOfLogId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IntegrationLog() {
    }

    IntegrationLog(String correlationId, IntegrationLogEntry entry, String requestPayload,
                   String responsePayload, String errorDescription, Instant createdAt) {
        this.correlationId = correlationId;
        this.integrationType = entry.type().name();
        this.operation = entry.operation();
        this.requestReference = entry.requestReference();
        this.outcome = entry.outcome().name();
        this.httpStatus = entry.httpStatus();
        this.durationMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, entry.durationMs()));
        this.errorCode = entry.errorCode();
        this.errorDescription = errorDescription;
        this.invoiceId = entry.invoiceId();
        this.paymentTransactionId = entry.paymentTransactionId();
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.retryable = entry.retryable();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getIntegrationType() {
        return integrationType;
    }

    public String getOperation() {
        return operation;
    }

    public String getRequestReference() {
        return requestReference;
    }

    public String getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public Long getPaymentTransactionId() {
        return paymentTransactionId;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Long getRetryOfLogId() {
        return retryOfLogId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
