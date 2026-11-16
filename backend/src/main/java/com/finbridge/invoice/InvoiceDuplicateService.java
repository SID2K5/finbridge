package com.finbridge.invoice;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds vendor bills that look like the same bill entered twice: same supplier, same vendor
 * reference, different Odoo records. Odoo itself only blocks this when the bill dates are
 * also equal, so a retyped bill with another date slips through and lands here.
 */
@Service
public class InvoiceDuplicateService {

    public record Group(Long supplierId, String vendorReference, List<Member> members) {
    }

    public record Member(Long invoiceId, String invoiceNumber, LocalDate invoiceDate) {
    }

    private final InvoiceRepository invoiceRepository;

    public InvoiceDuplicateService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public List<Group> findGroups() {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Invoice invoice : invoiceRepository.findWithSharedVendorReference()) {
            Long supplierId = invoice.getSupplier().getId();
            groups.computeIfAbsent(supplierId + "|" + invoice.getVendorReference(),
                            key -> new Group(supplierId, invoice.getVendorReference(), new ArrayList<>()))
                    .members().add(new Member(invoice.getId(), invoice.getInvoiceNumber(), invoice.getInvoiceDate()));
        }
        return List.copyOf(groups.values());
    }
}
