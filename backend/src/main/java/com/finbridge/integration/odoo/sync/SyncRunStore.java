package com.finbridge.integration.odoo.sync;

import com.finbridge.integration.odoo.history.SyncRun;
import com.finbridge.integration.odoo.history.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the sync_runs row. Each method commits on its own, so the counters of a
 * running synchronization are visible while it runs and survive a later failure.
 */
@Component
public class SyncRunStore {

    private final SyncRunRepository repository;
    private final Clock clock;

    public SyncRunStore(SyncRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean anyRunning() {
        return repository.existsByStatus(SyncRun.RUNNING);
    }

    /** Where the next incremental run starts: the cursor_to of the last run that advanced it. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastCursor() {
        return repository
                .findFirstByStatusInAndCursorToIsNotNullOrderByIdDesc(List.of(SyncRun.COMPLETED, SyncRun.PARTIAL))
                .map(SyncRun::getCursorTo);
    }

    @Transactional
    public SyncRun open(String triggerType, Long triggeredBy, Instant cursorFrom) {
        SyncRun run = new SyncRun(triggerType, triggeredBy, SyncRun.RUNNING, clock.instant());
        run.setCursorFrom(cursorFrom);
        return repository.save(run);
    }

    @Transactional
    public void addSuppliers(long runId, int upserted) {
        repository.findById(runId).orElseThrow().addSuppliersUpserted(upserted);
    }

    @Transactional
    public void addBills(long runId, SyncPageProcessor.BillPageResult page) {
        repository.findById(runId).orElseThrow()
                .addInvoiceCounts(page.created(), page.updated(), page.unchanged(), page.rejected());
    }

    @Transactional
    public void finish(long runId, String status, Instant cursorTo, String errorMessage) {
        String message = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage;
        repository.findById(runId).orElseThrow().finish(status, cursorTo, message, clock.instant());
    }

    /** A run still RUNNING at startup was cut off by a restart; close it so a new one can start. */
    @Transactional
    public int failInterruptedRuns() {
        List<SyncRun> interrupted = repository.findByStatus(SyncRun.RUNNING);
        interrupted.forEach(run -> run.finish(SyncRun.FAILED, null,
                "Interrupted: the application stopped while this run was in progress", clock.instant()));
        return interrupted.size();
    }
}
