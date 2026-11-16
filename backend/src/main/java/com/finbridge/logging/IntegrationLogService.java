package com.finbridge.logging;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only writer of integration_logs. Every gateway (Odoo now, SOAP and REST in Phase 4)
 * reports each outbound call here.
 *
 * A log row is written in its own transaction, so it survives even when a business
 * transaction around the call is rolled back. Failing to write a log never fails the call
 * it describes.
 */
@Service
public class IntegrationLogService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationLogService.class);

    private final IntegrationLogRepository repository;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public IntegrationLogService(IntegrationLogRepository repository,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stores the entry and returns its id, or null if it could not be stored. */
    public Long record(IntegrationLogEntry entry) {
        String correlationId = CorrelationIdFilter.currentId();
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        IntegrationLog row = new IntegrationLog(
                correlationId,
                entry,
                PayloadMasker.mask(entry.requestPayload()),
                PayloadMasker.mask(entry.responsePayload()),
                PayloadMasker.cap(PayloadMasker.mask(entry.errorDescription()), 1000),
                clock.instant());
        if (entry.outcome() == IntegrationLogEntry.Outcome.SUCCESS) {
            log.info("{} {} ok in {} ms", entry.type(), entry.operation(), entry.durationMs());
        } else {
            log.warn("{} {} {} in {} ms: {} {}", entry.type(), entry.operation(), entry.outcome(),
                    entry.durationMs(), entry.errorCode(), row.getErrorDescription());
        }
        try {
            return newTransaction.execute(status -> repository.save(row).getId());
        } catch (RuntimeException ex) {
            log.error("Could not store integration log for {} {}", entry.type(), entry.operation(), ex);
            return null;
        }
    }
}
