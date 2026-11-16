package com.finbridge.integration.odoo.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One execution of the Odoo synchronization: who started it, the write-date window it
 * covered (cursor_from to cursor_to), what it did, and how it ended.
 *
 * Status: RUNNING, then COMPLETED (everything processed), PARTIAL (some bills rejected, or
 * a page failed after earlier pages were saved) or FAILED (nothing usable).
 */
@Entity
@Table(name = "sync_runs")
public class SyncRun {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    @Column(name = "triggered_by")
    private Long triggeredBy;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "cursor_from")
    private Instant cursorFrom;

    @Column(name = "cursor_to")
    private Instant cursorTo;

    @Column(name = "suppliers_upserted", nullable = false)
    private int suppliersUpserted;

    @Column(name = "invoices_created", nullable = false)
    private int invoicesCreated;

    @Column(name = "invoices_updated", nullable = false)
    private int invoicesUpdated;

    @Column(name = "invoices_unchanged", nullable = false)
    private int invoicesUnchanged;

    @Column(name = "invoices_rejected", nullable = false)
    private int invoicesRejected;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected SyncRun() {
    }

    public SyncRun(String triggerType, Long triggeredBy, String status, Instant startedAt) {
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.status = status;
        this.startedAt = startedAt;
    }

    public void setCursorFrom(Instant cursorFrom) {
        this.cursorFrom = cursorFrom;
    }

    public void addSuppliersUpserted(int count) {
        this.suppliersUpserted += count;
    }

    public void addInvoiceCounts(int created, int updated, int unchanged, int rejected) {
        this.invoicesCreated += created;
        this.invoicesUpdated += updated;
        this.invoicesUnchanged += unchanged;
        this.invoicesRejected += rejected;
    }

    public void finish(String status, Instant cursorTo, String errorMessage, Instant finishedAt) {
        this.status = status;
        this.cursorTo = cursorTo;
        this.errorMessage = errorMessage;
        this.finishedAt = finishedAt;
    }

    public Long getId() {
        return id;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public Long getTriggeredBy() {
        return triggeredBy;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCursorFrom() {
        return cursorFrom;
    }

    public Instant getCursorTo() {
        return cursorTo;
    }

    public int getSuppliersUpserted() {
        return suppliersUpserted;
    }

    public int getInvoicesCreated() {
        return invoicesCreated;
    }

    public int getInvoicesUpdated() {
        return invoicesUpdated;
    }

    public int getInvoicesUnchanged() {
        return invoicesUnchanged;
    }

    public int getInvoicesRejected() {
        return invoicesRejected;
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
