package com.finbridge.invoice;

import com.finbridge.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the payment module may do to an invoice: lock it while deciding whether it can be
 * paid, and move its payment status. Both run inside the payment module's own transaction.
 */
@Service
public class InvoicePaymentService {

    private final InvoiceRepository invoiceRepository;

    public InvoicePaymentService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /** Locks the invoice row until the surrounding transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice lock(Long invoiceId) {
        return invoiceRepository.findForUpdateById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice " + invoiceId + " was not found"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setStatus(Invoice invoice, InvoiceStatus status) {
        invoice.setInvoiceStatus(status);
    }
}
