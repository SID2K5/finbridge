package com.finbridge.invoice;

import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.odoo.history.SyncHistoryQueryService;
import com.finbridge.integration.odoo.history.SyncHistoryResponse;
import com.finbridge.invoice.dto.InvoiceDetailResponse;
import com.finbridge.invoice.dto.InvoiceSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final SyncHistoryQueryService syncHistoryQueryService;
    private final InvoicePaymentsProvider paymentsProvider;

    public InvoiceService(InvoiceRepository invoiceRepository, SyncHistoryQueryService syncHistoryQueryService,
                          InvoicePaymentsProvider paymentsProvider) {
        this.invoiceRepository = invoiceRepository;
        this.syncHistoryQueryService = syncHistoryQueryService;
        this.paymentsProvider = paymentsProvider;
    }

    public Page<InvoiceSummaryResponse> search(InvoiceFilter filter, Pageable pageable) {
        if (filter.dateFrom() != null && filter.dateTo() != null && filter.dateFrom().isAfter(filter.dateTo())) {
            throw new InvalidRequestException("dateFrom must not be after dateTo");
        }
        return invoiceRepository.findAll(InvoiceSpecifications.from(filter), pageable)
                .map(InvoiceSummaryResponse::from);
    }

    public InvoiceDetailResponse get(Long id) {
        return invoiceRepository.findWithSupplierById(id)
                .map(invoice -> InvoiceDetailResponse.from(invoice, paymentsProvider.forInvoice(invoice.getId())))
                .orElseThrow(() -> notFound(id));
    }

    public Page<SyncHistoryResponse> history(Long id, Pageable pageable) {
        if (!invoiceRepository.existsById(id)) {
            throw notFound(id);
        }
        return syncHistoryQueryService.forInvoice(id, pageable);
    }

    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Invoice " + id + " was not found");
    }
}
