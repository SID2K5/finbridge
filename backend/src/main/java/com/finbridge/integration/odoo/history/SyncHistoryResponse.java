package com.finbridge.integration.odoo.history;

import java.time.Instant;

public record SyncHistoryResponse(
        Long id,
        Long syncRunId,
        String entityType,
        Long odooRecordId,
        String action,
        String message,
        Instant createdAt) {

    public static SyncHistoryResponse from(SynchronizationHistory entry) {
        return new SyncHistoryResponse(entry.getId(), entry.getSyncRunId(), entry.getEntityType(),
                entry.getOdooRecordId(), entry.getAction(), entry.getMessage(), entry.getCreatedAt());
    }
}
