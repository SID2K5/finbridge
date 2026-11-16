package com.finbridge.integration.monitoring;

import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.logging.IntegrationLog;
import com.finbridge.logging.IntegrationLogEntry;
import com.finbridge.logging.IntegrationLogRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IntegrationLogQueryService {

    public record Filter(IntegrationLogEntry.Type type, IntegrationLogEntry.Outcome outcome, Instant from,
                         Instant to, String reference, String correlationId) {
    }

    /** List row: no payloads, so a page of logs stays small. */
    public record Summary(Long id, Instant createdAt, String type, String operation, String requestReference,
                          String outcome, Integer httpStatus, int durationMs, String errorCode,
                          String correlationId, boolean retryable) {

        static Summary from(IntegrationLog row) {
            return new Summary(row.getId(), row.getCreatedAt(), row.getIntegrationType(), row.getOperation(),
                    row.getRequestReference(), row.getOutcome(), row.getHttpStatus(), row.getDurationMs(),
                    row.getErrorCode(), row.getCorrelationId(), row.isRetryable());
        }
    }

    /** Detail: everything, with payloads as stored (already masked and size-capped). */
    public record Detail(Long id, Instant createdAt, String type, String operation, String requestReference,
                         String outcome, Integer httpStatus, int durationMs, String errorCode,
                         String errorDescription, String correlationId, boolean retryable, Long retryOfLogId,
                         Long invoiceId, Long paymentTransactionId, String requestPayload,
                         String responsePayload) {

        static Detail from(IntegrationLog row) {
            return new Detail(row.getId(), row.getCreatedAt(), row.getIntegrationType(), row.getOperation(),
                    row.getRequestReference(), row.getOutcome(), row.getHttpStatus(), row.getDurationMs(),
                    row.getErrorCode(), row.getErrorDescription(), row.getCorrelationId(), row.isRetryable(),
                    row.getRetryOfLogId(), row.getInvoiceId(), row.getPaymentTransactionId(),
                    row.getRequestPayload(), row.getResponsePayload());
        }
    }

    private final IntegrationLogRepository repository;

    public IntegrationLogQueryService(IntegrationLogRepository repository) {
        this.repository = repository;
    }

    public Page<Summary> search(Filter filter, Pageable pageable) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new InvalidRequestException("from must not be after to");
        }
        Specification<IntegrationLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("integrationType"), filter.type().name()));
            }
            if (filter.outcome() != null) {
                predicates.add(cb.equal(root.get("outcome"), filter.outcome().name()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.to()));
            }
            if (filter.reference() != null && !filter.reference().isBlank()) {
                predicates.add(cb.equal(root.get("requestReference"), filter.reference().trim()));
            }
            if (filter.correlationId() != null && !filter.correlationId().isBlank()) {
                predicates.add(cb.equal(root.get("correlationId"), filter.correlationId().trim()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable).map(Summary::from);
    }

    public Detail get(Long id) {
        return repository.findById(id).map(Detail::from)
                .orElseThrow(() -> new ResourceNotFoundException("Integration log " + id + " was not found"));
    }
}
