package com.finbridge.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The only writer of the two reconciliation tables. */
@Component
public class ReconciliationStore {

    private final ReconciliationRunRepository runs;
    private final ReconciliationRecordRepository records;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReconciliationStore(ReconciliationRunRepository runs, ReconciliationRecordRepository records,
                               ObjectMapper objectMapper, Clock clock) {
        this.runs = runs;
        this.records = records;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ReconciliationRun open(Long userId, LocalDate from, LocalDate to) {
        return runs.save(new ReconciliationRun(userId, from, to, clock.instant()));
    }

    /** Saves every finding and closes the run in one transaction: a run is complete or it has no records. */
    @Transactional
    public void complete(Long runId, List<ReconciliationEngine.Finding> findings) {
        Instant now = clock.instant();
        int matched = 0;
        for (ReconciliationEngine.Finding finding : findings) {
            if (finding.status() == ReconciliationRecord.Status.MATCHED) {
                matched++;
            }
            records.save(new ReconciliationRecord(runId, finding, internalSnapshot(finding.internal()),
                    externalSnapshot(finding.external()), now));
        }
        runs.findById(runId).orElseThrow().complete(findings.size(), matched, now);
    }

    @Transactional
    public void fail(Long runId, String message) {
        runs.findById(runId).orElseThrow().fail(message, clock.instant());
    }

    private String internalSnapshot(ReconciliationEngine.Internal payment) {
        if (payment == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionReference", payment.transactionReference());
        snapshot.put("invoiceReference", payment.invoiceNumber());
        snapshot.put("supplierReference", payment.supplierReference());
        snapshot.put("currency", payment.currency());
        snapshot.put("amount", payment.requestedAmount());
        snapshot.put("status", payment.status());
        snapshot.put("externalTransactionId", payment.externalTransactionId());
        snapshot.put("processedAmount", payment.processedAmount());
        snapshot.put("failureReason", payment.failureReason());
        snapshot.put("timestamp", payment.requestedAt());
        return json(snapshot);
    }

    private String externalSnapshot(ReconciliationEngine.External entry) {
        if (entry == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionReference", entry.transactionReference());
        snapshot.put("invoiceReference", entry.invoiceReference());
        snapshot.put("supplierReference", entry.supplierReference());
        snapshot.put("currency", entry.currency());
        snapshot.put("amount", entry.processedAmount());
        snapshot.put("status", entry.status());
        snapshot.put("externalTransactionId", entry.externalTransactionId());
        snapshot.put("timestamp", entry.responseTimestamp());
        return json(snapshot);
    }

    private String json(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store a reconciliation snapshot", ex);
        }
    }
}
