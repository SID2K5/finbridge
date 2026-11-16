package com.finbridge.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.invoice.InvoiceDuplicateService;
import com.finbridge.logging.CorrelationIdFilter;
import com.finbridge.payment.PaymentQueryService;
import com.finbridge.payment.PaymentSnapshot;
import com.finbridge.reconciliation.ReconciliationRecord.ReviewStatus;
import com.finbridge.reconciliation.ReconciliationRecord.Status;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs reconciliations and answers questions about them.
 *
 * A run compares FinBridge's payments of a period with the payment service's settlement
 * statement (fetched over SOAP) and with the duplicate-bill check, and stores one record per
 * finding. It changes nothing else: no invoice and no payment is ever touched, and a review
 * only records a person's decision on a record.
 */
@Service
public class ReconciliationService {

    public static final String EXECUTOR = "reconciliationExecutor";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int MAX_PERIOD_DAYS = 366;

    // ---------- API views ----------

    public record RunResponse(Long id, Long triggeredBy, LocalDate periodFrom, LocalDate periodTo, String status,
                              int totalRecords, int matchedCount, int discrepancyCount, String errorMessage,
                              Instant startedAt, Instant finishedAt, Map<String, Long> countsByStatus) {
    }

    public record RecordResponse(Long id, Long runId, String status, String reviewStatus, Long invoiceId,
                                 Long paymentTransactionId, String internalTransactionReference,
                                 String externalTransactionReference, BigDecimal expectedAmount,
                                 BigDecimal actualAmount, String currency, String discrepancyDescription,
                                 Instant reconciledAt, Instant reviewedAt) {

        static RecordResponse from(ReconciliationRecord record) {
            return new RecordResponse(record.getId(), record.getRunId(), record.getStatus().name(),
                    record.getReviewStatus().name(), record.getInvoiceId(), record.getPaymentTransactionId(),
                    record.getInternalTransactionReference(), record.getExternalTransactionReference(),
                    record.getExpectedAmount(), record.getActualAmount(), record.getCurrency(),
                    record.getDiscrepancyDescription(), record.getReconciledAt(), record.getReviewedAt());
        }
    }

    /** One compared field. matches is null when one side is missing or the field is not comparable. */
    public record FieldComparison(String field, String internal, String external, Boolean matches) {
    }

    public record RecordDetail(RecordResponse record, String reviewNote, Long reviewedBy,
                               List<FieldComparison> comparison) {
    }

    public record Filter(Long runId, Status status, ReviewStatus reviewStatus, Long invoiceId) {
    }

    // ---------- wiring ----------

    private final ReconciliationStore store;
    private final ReconciliationRunRepository runs;
    private final ReconciliationRecordRepository records;
    private final PaymentGateway gateway;
    private final PaymentQueryService payments;
    private final InvoiceDuplicateService duplicates;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReconciliationService(ReconciliationStore store, ReconciliationRunRepository runs,
                                 ReconciliationRecordRepository records, PaymentGateway gateway,
                                 PaymentQueryService payments, InvoiceDuplicateService duplicates,
                                 @Qualifier(EXECUTOR) TaskExecutor executor, ObjectMapper objectMapper,
                                 Clock clock) {
        this.store = store;
        this.runs = runs;
        this.records = records;
        this.gateway = gateway;
        this.payments = payments;
        this.duplicates = duplicates;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- running ----------

    /** Opens the run and executes it in the background; the caller polls the run until it is no longer RUNNING. */
    public RunResponse start(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidRequestException("periodFrom and periodTo are required (yyyy-MM-dd)");
        }
        if (from.isAfter(to)) {
            throw new InvalidRequestException("periodFrom must not be after periodTo");
        }
        if (from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
            throw new InvalidRequestException("The period may cover at most " + MAX_PERIOD_DAYS + " days");
        }
        ReconciliationRun run = store.open(userId, from, to);
        Long runId = run.getId();
        String correlationId = CorrelationIdFilter.currentId();
        executor.execute(() -> {
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                execute(runId, from, to);
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        });
        return toResponse(run, Map.of());
    }

    void execute(Long runId, LocalDate from, LocalDate to) {
        try {
            List<ReconciliationEngine.Finding> findings = reconcile(from, to);
            store.complete(runId, findings);
            log.info("Reconciliation run {} for {} to {} completed with {} record(s)", runId, from, to,
                    findings.size());
        } catch (PaymentGatewayException ex) {
            store.fail(runId, "The settlement statement could not be fetched: " + ex.getErrorCode() + " - "
                    + ex.getMessage());
            log.warn("Reconciliation run {} failed: {}", runId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Reconciliation run {} failed unexpectedly", runId, ex);
            store.fail(runId, "The run stopped on an unexpected error. Search the application log for "
                    + "correlation id " + CorrelationIdFilter.currentId());
        }
    }

    private List<ReconciliationEngine.Finding> reconcile(LocalDate from, LocalDate to) {
        Instant periodStart = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodEnd = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // The statement is fetched one day wider on each side: a payment requested just before
        // midnight is settled just after it, and must still find its entry.
        List<ReconciliationEngine.External> statement = gateway.statement(from.minusDays(1), to.plusDays(1))
                .stream().map(ReconciliationService::external).toList();
        List<ReconciliationEngine.External> inPeriod = statement.stream()
                .filter(entry -> !entry.responseTimestamp().isBefore(periodStart)
                        && entry.responseTimestamp().isBefore(periodEnd))
                .toList();

        // Payments requested in the period, plus the payments behind the period's statement entries.
        Set<String> references = inPeriod.stream().map(ReconciliationEngine.External::transactionReference)
                .collect(Collectors.toSet());
        List<ReconciliationEngine.Internal> internal = payments.forReconciliation(periodStart, periodEnd, references)
                .stream().map(ReconciliationService::internal).toList();

        // Duplicate bills are reported when one of them was paid in this run or is dated in the period.
        Set<Long> paidInvoices = internal.stream().map(ReconciliationEngine.Internal::invoiceId)
                .collect(Collectors.toSet());
        List<ReconciliationEngine.DuplicateGroup> groups = duplicates.findGroups().stream()
                .filter(group -> group.members().stream().anyMatch(member -> paidInvoices.contains(member.invoiceId())
                        || (!member.invoiceDate().isBefore(from) && !member.invoiceDate().isAfter(to))))
                .map(group -> new ReconciliationEngine.DuplicateGroup(group.vendorReference(),
                        group.members().stream().map(member -> new ReconciliationEngine.DuplicateGroup.Member(
                                member.invoiceId(), member.invoiceNumber())).toList()))
                .toList();

        return ReconciliationEngine.reconcile(internal, statement, inPeriod, groups);
    }

    private static ReconciliationEngine.Internal internal(PaymentSnapshot payment) {
        return new ReconciliationEngine.Internal(payment.id(), payment.invoiceId(), payment.invoiceNumber(),
                payment.supplierReference(), payment.transactionReference(), payment.externalTransactionId(),
                payment.requestedAmount(), payment.processedAmount(), payment.currency(), payment.status().name(),
                payment.failureReason(), payment.requestedAt());
    }

    private static ReconciliationEngine.External external(PaymentGateway.StatementEntry entry) {
        PaymentGateway.Outcome outcome = entry.outcome();
        return new ReconciliationEngine.External(outcome.externalTransactionId(), outcome.transactionReference(),
                outcome.invoiceReference(), entry.supplierReference(), outcome.processedAmount(), outcome.currency(),
                outcome.status().name(), outcome.responseTimestamp());
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    public Page<RunResponse> listRuns(Pageable pageable) {
        return runs.findAll(pageable).map(run -> toResponse(run, Map.of()));
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(Long id) {
        ReconciliationRun run = runs.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("Reconciliation run " + id + " was not found"));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), records.count((root, query, cb) -> cb.and(
                    cb.equal(root.get("runId"), id), cb.equal(root.get("status"), status))));
        }
        return toResponse(run, counts);
    }

    /** Records of one run; without a runId, of the latest run. */
    @Transactional(readOnly = true)
    public Page<RecordResponse> search(Filter filter, Pageable pageable) {
        Long runId = filter.runId() != null ? filter.runId()
                : runs.findFirstByOrderByIdDesc().map(ReconciliationRun::getId).orElse(-1L);
        Specification<ReconciliationRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("runId"), runId));
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.reviewStatus() != null) {
                predicates.add(cb.equal(root.get("reviewStatus"), filter.reviewStatus()));
            }
            if (filter.invoiceId() != null) {
                predicates.add(cb.equal(root.get("invoiceId"), filter.invoiceId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return records.findAll(spec, pageable).map(RecordResponse::from);
    }

    @Transactional(readOnly = true)
    public RecordDetail getRecord(Long id) {
        return detail(load(id));
    }

    // ---------- review ----------

    /** Records a person's decision on a discrepancy. Nothing but the record itself changes. */
    @Transactional
    public RecordDetail review(Long id, ReviewStatus decision, String note, Long reviewerId) {
        if (decision != ReviewStatus.RESOLVED && decision != ReviewStatus.NEEDS_INVESTIGATION) {
            throw new InvalidRequestException("reviewStatus must be RESOLVED or NEEDS_INVESTIGATION");
        }
        ReconciliationRecord record = load(id);
        if (record.getReviewStatus() == ReviewStatus.NOT_REQUIRED) {
            throw new BusinessRuleException("This record is MATCHED and needs no review");
        }
        record.review(decision, note.trim(), reviewerId, clock.instant());
        return detail(record);
    }

    private ReconciliationRecord load(Long id) {
        return records.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("Reconciliation record " + id + " was not found"));
    }

    private RecordDetail detail(ReconciliationRecord record) {
        JsonNode internal = parse(record.getInternalSnapshot());
        JsonNode external = parse(record.getExternalSnapshot());
        boolean settled = external != null && "SUCCESS".equals(text(external, "status"));
        List<FieldComparison> comparison = new ArrayList<>();
        for (String field : List.of("transactionReference", "invoiceReference", "supplierReference", "currency",
                "amount", "status", "externalTransactionId", "timestamp")) {
            String ours = text(internal, field);
            String theirs = text(external, field);
            Boolean matches = null;
            if (ours != null && theirs != null && !field.equals("timestamp")) {
                if (field.equals("amount")) {
                    // requested against settled: only a settled payment has an amount to compare
                    matches = settled ? new BigDecimal(ours).compareTo(new BigDecimal(theirs)) == 0 : null;
                } else {
                    matches = ours.equals(theirs);
                }
            }
            comparison.add(new FieldComparison(field, ours, theirs, matches));
        }
        return new RecordDetail(RecordResponse.from(record), record.getReviewNote(), record.getReviewedBy(),
                comparison);
    }

    private JsonNode parse(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.readTree(snapshot);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isNumber() ? value.decimalValue().toPlainString() : value.asText();
    }

    private static RunResponse toResponse(ReconciliationRun run, Map<String, Long> counts) {
        return new RunResponse(run.getId(), run.getTriggeredBy(), run.getPeriodFrom(), run.getPeriodTo(),
                run.getStatus(), run.getTotalRecords(), run.getMatchedCount(), run.getDiscrepancyCount(),
                run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(), counts.isEmpty() ? null : counts);
    }
}
