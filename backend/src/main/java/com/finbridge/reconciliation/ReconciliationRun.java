package com.finbridge.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** One execution of the reconciliation for a period: who started it, and what it found. */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private Long triggeredBy;

    @Column(name = "period_from", nullable = false, updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false, updatable = false)
    private LocalDate periodTo;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(Long triggeredBy, LocalDate periodFrom, LocalDate periodTo, Instant startedAt) {
        this.triggeredBy = triggeredBy;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.status = RUNNING;
        this.startedAt = startedAt;
    }

    public void complete(int totalRecords, int matchedCount, Instant finishedAt) {
        this.status = COMPLETED;
        this.totalRecords = totalRecords;
        this.matchedCount = matchedCount;
        this.discrepancyCount = totalRecords - matchedCount;
        this.finishedAt = finishedAt;
    }

    public void fail(String errorMessage, Instant finishedAt) {
        this.status = FAILED;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage;
        this.finishedAt = finishedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTriggeredBy() {
        return triggeredBy;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getDiscrepancyCount() {
        return discrepancyCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
