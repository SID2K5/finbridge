package com.finbridge.integration.odoo.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynchronizationHistoryRepository extends JpaRepository<SynchronizationHistory, Long> {

    Page<SynchronizationHistory> findByInvoiceId(Long invoiceId, Pageable pageable);

    Page<SynchronizationHistory> findBySyncRunId(Long syncRunId, Pageable pageable);
}
