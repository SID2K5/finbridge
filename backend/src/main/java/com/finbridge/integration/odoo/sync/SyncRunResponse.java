package com.finbridge.integration.odoo.sync;

import com.finbridge.integration.odoo.history.SyncRun;
import java.time.Instant;

public record SyncRunResponse(
        Long id,
        String triggerType,
        Long triggeredBy,
        String status,
        Instant cursorFrom,
        Instant cursorTo,
        int suppliersUpserted,
        int invoicesCreated,
        int invoicesUpdated,
        int invoicesUnchanged,
        int invoicesRejected,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    public static SyncRunResponse from(SyncRun run) {
        return new SyncRunResponse(run.getId(), run.getTriggerType(), run.getTriggeredBy(), run.getStatus(),
                run.getCursorFrom(), run.getCursorTo(), run.getSuppliersUpserted(), run.getInvoicesCreated(),
                run.getInvoicesUpdated(), run.getInvoicesUnchanged(), run.getInvoicesRejected(),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt());
    }
}
