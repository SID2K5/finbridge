package com.finbridge.integration.odoo.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** What one synchronization run did with one Odoo record (created, updated, unchanged, rejected...). */
@Entity
@Table(name = "synchronization_history")
public class SynchronizationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_run_id", nullable = false)
    private Long syncRunId;

    @Column(name = "entity_type", nullable = false, length = 16)
    private String entityType;

    @Column(name = "odoo_record_id", nullable = false)
    private Long odooRecordId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(nullable = false, length = 24)
    private String action;

    @Column(length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SynchronizationHistory() {
    }

    public SynchronizationHistory(Long syncRunId, String entityType, Long odooRecordId, Long invoiceId,
                                  String action, String message) {
        this.syncRunId = syncRunId;
        this.entityType = entityType;
        this.odooRecordId = odooRecordId;
        this.invoiceId = invoiceId;
        this.action = action;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSyncRunId() {
        return syncRunId;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getOdooRecordId() {
        return odooRecordId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public String getAction() {
        return action;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
