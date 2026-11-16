package com.finbridge.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository
        extends JpaRepository<PaymentTransaction, Long>, JpaSpecificationExecutor<PaymentTransaction> {

    @Override
    @EntityGraph(attributePaths = {"invoice", "supplier"})
    Page<PaymentTransaction> findAll(Specification<PaymentTransaction> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"invoice", "supplier"})
    Optional<PaymentTransaction> findWithInvoiceById(Long id);

    @EntityGraph(attributePaths = {"invoice", "supplier"})
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByInvoiceIdAndStatusInAndIdNot(Long invoiceId, Collection<PaymentStatus> statuses, Long id);

    boolean existsByInvoiceIdAndStatusIn(Long invoiceId, Collection<PaymentStatus> statuses);

    List<PaymentTransaction> findByInvoiceIdOrderByIdDesc(Long invoiceId);

    @Query("select p.invoice.id from PaymentTransaction p where p.id = :id")
    Optional<Long> findInvoiceIdById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"invoice", "supplier"})
    List<PaymentTransaction> findByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderById(
            Instant fromInclusive, Instant toExclusive);

    @EntityGraph(attributePaths = {"invoice", "supplier"})
    List<PaymentTransaction> findByTransactionReferenceIn(Collection<String> references);
}
