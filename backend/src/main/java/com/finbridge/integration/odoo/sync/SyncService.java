package com.finbridge.integration.odoo.sync;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.ConflictException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.odoo.OdooGateway;
import com.finbridge.integration.odoo.history.SyncHistoryResponse;
import com.finbridge.integration.odoo.history.SyncRun;
import com.finbridge.integration.odoo.history.SyncRunRepository;
import com.finbridge.integration.odoo.history.SynchronizationHistoryRepository;
import com.finbridge.logging.CorrelationIdFilter;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Starts synchronization runs and answers questions about them.
 *
 * A run is started here and executed on a background thread, so the HTTP request returns
 * 202 at once with the run to poll. Only one run may be RUNNING at a time.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final OdooGateway gateway;
    private final SyncRunStore runStore;
    private final SyncRunner runner;
    private final SyncRunRepository runRepository;
    private final SynchronizationHistoryRepository historyRepository;
    private final TaskExecutor executor;

    public SyncService(OdooGateway gateway, SyncRunStore runStore, SyncRunner runner,
                       SyncRunRepository runRepository, SynchronizationHistoryRepository historyRepository,
                       @Qualifier(SyncConfig.EXECUTOR) TaskExecutor executor) {
        this.gateway = gateway;
        this.runStore = runStore;
        this.runner = runner;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void closeRunsInterruptedByRestart() {
        int closed = runStore.failInterruptedRuns();
        if (closed > 0) {
            log.warn("Marked {} synchronization run(s) left RUNNING by a previous shutdown as FAILED", closed);
        }
    }

    /**
     * @param fullResync true ignores the cursor and reads every supplier and posted bill again;
     *                   records that did not change are reported as unchanged.
     */
    public synchronized SyncRunResponse start(Long triggeredBy, boolean fullResync) {
        if (!gateway.isConfigured()) {
            throw new BusinessRuleException("Odoo is not configured: ODOO_API_KEY is missing. "
                    + "Create an API key for the integration user in Odoo, add it to .env and restart");
        }
        if (runStore.anyRunning()) {
            throw new ConflictException("A synchronization run is already in progress");
        }
        Instant cursorFrom = fullResync ? null : runStore.lastCursor().orElse(null);
        SyncRun run = runStore.open("MANUAL", triggeredBy, cursorFrom);
        long runId = run.getId();
        String correlationId = CorrelationIdFilter.currentId();
        try {
            executor.execute(() -> execute(runId, cursorFrom, correlationId));
        } catch (TaskRejectedException ex) {
            runStore.finish(runId, SyncRun.FAILED, null, "The run could not be scheduled");
            throw new ConflictException("A synchronization run is already in progress");
        }
        return SyncRunResponse.from(run);
    }

    private void execute(long runId, Instant cursorFrom, String correlationId) {
        // The background thread keeps the correlation id of the request that started the run,
        // so the request, the run and every Odoo call can be found with one search.
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            runner.run(runId, cursorFrom);
        } catch (RuntimeException ex) {
            log.error("Sync run {} could not be closed normally", runId, ex);
            try {
                runStore.finish(runId, SyncRun.FAILED, null, "The run stopped on an unexpected error");
            } catch (RuntimeException closeFailure) {
                log.error("Sync run {} could not be marked FAILED", runId, closeFailure);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    public Page<SyncRunResponse> list(Pageable pageable) {
        return runRepository.findAll(pageable).map(SyncRunResponse::from);
    }

    public SyncRunResponse get(Long id) {
        return runRepository.findById(id).map(SyncRunResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Synchronization run " + id + " was not found"));
    }

    public Page<SyncHistoryResponse> history(Long runId, Pageable pageable) {
        if (!runRepository.existsById(runId)) {
            throw new ResourceNotFoundException("Synchronization run " + runId + " was not found");
        }
        return historyRepository.findBySyncRunId(runId, pageable).map(SyncHistoryResponse::from);
    }

    public Optional<SyncRunResponse> latest() {
        return runRepository.findFirstByOrderByIdDesc().map(SyncRunResponse::from);
    }
}
