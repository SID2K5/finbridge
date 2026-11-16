package com.finbridge.logging;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IntegrationLogRepository
        extends JpaRepository<IntegrationLog, Long>, JpaSpecificationExecutor<IntegrationLog> {

    Optional<IntegrationLog> findFirstByIntegrationTypeOrderByIdDesc(String integrationType);

    List<IntegrationLog> findByPaymentTransactionIdOrderByIdAsc(Long paymentTransactionId);

    Optional<IntegrationLog> findFirstByIntegrationTypeAndOutcomeNotOrderByIdDesc(String integrationType,
                                                                                String outcome);
}
