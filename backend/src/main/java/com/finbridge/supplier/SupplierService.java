package com.finbridge.supplier;

import com.finbridge.exception.ResourceNotFoundException;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public Page<SupplierResponse> search(String q, Pageable pageable) {
        Specification<Supplier> filter = null;
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filter = (root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<String>get("name")), pattern, '!'),
                    cb.like(cb.lower(root.<String>get("taxId")), pattern, '!'));
        }
        return supplierRepository.findAll(filter, pageable).map(SupplierResponse::from);
    }

    public SupplierResponse get(Long id) {
        return supplierRepository.findById(id)
                .map(SupplierResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier " + id + " was not found"));
    }
}
