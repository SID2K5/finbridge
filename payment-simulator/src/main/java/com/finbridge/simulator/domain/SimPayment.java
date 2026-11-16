package com.finbridge.simulator.domain;

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

/** A payment as the simulated provider recorded it. This is the "external" side of reconciliation. */
@Entity
@Table(name = "sim_payments")
public class SimPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String externalTransactionId;

    @Column(nullable = false, unique = true, length = 40)
    private String transactionReference;

    @Column(nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String payloadHash;

    @Column(nullable = false, length = 64)
    private String invoiceReference;

    @Column(nullable = false, length = 64)
    private String supplierReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal processedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Scenario scenario;

    @Column(nullable = false)
    private int inquiryCount;

    @Column(nullable = false)
    private int submissions;

    @Column(nullable = false)
    private boolean orphan;

    @Column(nullable = false)
    private Instant responseTimestamp;

    protected SimPayment() {
    }

    public SimPayment(String externalTransactionId, String transactionReference, String invoiceReference,
                      String supplierReference, BigDecimal requestedAmount, String currency) {
        this.externalTransactionId = externalTransactionId;
        this.transactionReference = transactionReference;
        this.invoiceReference = invoiceReference;
        this.supplierReference = supplierReference;
        this.requestedAmount = requestedAmount;
        this.currency = currency;
    }

    /** Records one accepted submission and its outcome. */
    public void process(String idempotencyKey, String payloadHash, Scenario scenario, String status,
                        BigDecimal processedAmount, String failureReason, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.scenario = scenario;
        this.status = status;
        this.processedAmount = processedAmount;
        this.failureReason = failureReason;
        this.responseTimestamp = now;
        this.inquiryCount = 0;
        this.submissions++;
    }

    public void settle(Instant now) {
        this.status = "SUCCESS";
        this.processedAmount = requestedAmount;
        this.responseTimestamp = now;
    }

    public void countInquiry() {
        this.inquiryCount++;
    }

    public void markOrphan() {
        this.orphan = true;
    }

    public Long getId() {
        return id;
    }

    public String getExternalTransactionId() {
        return externalTransactionId;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getInvoiceReference() {
        return invoiceReference;
    }

    public String getSupplierReference() {
        return supplierReference;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getProcessedAmount() {
        return processedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Scenario getScenario() {
        return scenario;
    }

    public int getInquiryCount() {
        return inquiryCount;
    }

    public int getSubmissions() {
        return submissions;
    }

    public boolean isOrphan() {
        return orphan;
    }

    public Instant getResponseTimestamp() {
        return responseTimestamp;
    }
}
