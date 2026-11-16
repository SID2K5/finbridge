package com.finbridge.integration.odoo.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only way other modules read synchronization history; they never touch its repositories. */
@Service
@Transactional(readOnly = true)
public class SyncHistoryQueryService {

    private final SynchronizationHistoryRepository historyRepository;

    public SyncHistoryQueryService(SynchronizationHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public Page<SyncHistoryResponse> forInvoice(Long invoiceId, Pageable pageable) {
        return historyRepository.findByInvoiceId(invoiceId, pageable).map(SyncHistoryResponse::from);
    }
}
