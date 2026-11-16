package com.finbridge.payment;

import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.invoice.InvoicePaymentSummary;
import com.finbridge.invoice.InvoicePaymentsProvider;
import com.finbridge.logging.IntegrationLogRepository;
import com.finbridge.payment.dto.PaymentDetailResponse;
import com.finbridge.payment.dto.PaymentResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the payment module. Makes no outbound call. */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService implements InvoicePaymentsProvider {

    public record Filter(PaymentStatus status, Long invoiceId, Long supplierId, Instant from, Instant to,
                         String reference) {
    }

    private final PaymentRepository repository;
    private final IntegrationLogRepository logRepository;

    public PaymentQueryService(PaymentRepository repository, IntegrationLogRepository logRepository) {
        this.repository = repository;
        this.logRepository = logRepository;
    }

    public Page<PaymentResponse> search(Filter filter, Pageable pageable) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new InvalidRequestException("from must not be after to");
        }
        Specification<PaymentTransaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.invoiceId() != null) {
                predicates.add(cb.equal(root.get("invoice").get("id"), filter.invoiceId()));
            }
            if (filter.supplierId() != null) {
                predicates.add(cb.equal(root.get("supplier").get("id"), filter.supplierId()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("requestedAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("requestedAt"), filter.to()));
            }
            if (filter.reference() != null && !filter.reference().isBlank()) {
                String pattern = "%" + filter.reference().trim().toUpperCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.upper(root.<String>get("transactionReference")), pattern, '!'),
                        cb.like(cb.upper(root.<String>get("externalTransactionId")), pattern, '!')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable).map(PaymentResponse::from);
    }

    public PaymentResponse get(Long id) {
        return PaymentResponse.from(load(id));
    }

    public PaymentDetailResponse detail(Long id) {
        PaymentTransaction tx = load(id);
        List<PaymentDetailResponse.Attempt> attempts = logRepository.findByPaymentTransactionIdOrderByIdAsc(id)
                .stream().map(PaymentDetailResponse.Attempt::from).toList();
        return new PaymentDetailResponse(PaymentResponse.from(tx), attempts);
    }

    @Override
    public List<InvoicePaymentSummary> forInvoice(Long invoiceId) {
        return repository.findByInvoiceIdOrderByIdDesc(invoiceId).stream()
                .map(tx -> new InvoicePaymentSummary(tx.getId(), tx.getTransactionReference(), tx.getStatus().name(),
                        tx.getRequestedAmount(), tx.getProcessedAmount(), tx.getCurrency(), tx.getFailureReason(),
                        tx.getAttemptCount(), tx.getRequestedAt(), tx.getRespondedAt()))
                .toList();
    }

    /**
     * For reconciliation: the payments requested in [from, to), plus any payment with one of
     * the given references whenever it was requested.
     */
    public List<PaymentSnapshot> forReconciliation(Instant fromInclusive, Instant toExclusive,
                                                   java.util.Collection<String> alsoReferences) {
        java.util.Map<Long, PaymentTransaction> found = new java.util.LinkedHashMap<>();
        repository.findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderById(fromInclusive, toExclusive)
                .forEach(tx -> found.put(tx.getId(), tx));
        if (!alsoReferences.isEmpty()) {
            repository.findByTransactionReferenceIn(alsoReferences).forEach(tx -> found.putIfAbsent(tx.getId(), tx));
        }
        return found.values().stream().map(PaymentSnapshot::from).toList();
    }

    private PaymentTransaction load(Long id) {
        return repository.findWithInvoiceById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + id + " was not found"));
    }
}
