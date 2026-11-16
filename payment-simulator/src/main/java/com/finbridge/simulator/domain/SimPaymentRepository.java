package com.finbridge.simulator.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimPaymentRepository extends JpaRepository<SimPayment, Long> {

    Optional<SimPayment> findByIdempotencyKey(String idempotencyKey);

    Optional<SimPayment> findByTransactionReference(String transactionReference);

    List<SimPayment> findByInvoiceReferenceOrderById(String invoiceReference);

    List<SimPayment> findByResponseTimestampGreaterThanEqualAndResponseTimestampLessThanOrderById(
            Instant fromInclusive, Instant toExclusive);
}
