package com.finbridge.supplier;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of the supplier module, used only by synchronization. A supplier is
 * identified by its Odoo partner id: the same partner is updated in place and can never
 * become a second row (the database also enforces this with a unique key).
 */
@Service
public class SupplierSyncService {

    public enum Result { CREATED, UPDATED, UNCHANGED }

    private final SupplierRepository supplierRepository;

    public SupplierSyncService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    /** Null when the record is acceptable, otherwise why it is not. */
    public String rejectionReason(SupplierSyncData data) {
        if (data.name() == null || data.name().isBlank()) {
            return "Partner has no name";
        }
        if (data.odooWriteDate() == null) {
            return "Partner has no write date";
        }
        return null;
    }

    @Transactional
    public Result upsert(SupplierSyncData data) {
        Optional<Supplier> existing = supplierRepository.findByOdooPartnerId(data.odooPartnerId());
        if (existing.isEmpty()) {
            Supplier supplier = new Supplier(data.odooPartnerId(), limit(data.name(), 255), data.odooWriteDate());
            copy(data, supplier);
            supplierRepository.save(supplier);
            return Result.CREATED;
        }
        Supplier supplier = existing.get();
        if (!data.odooWriteDate().isAfter(supplier.getOdooWriteDate())) {
            return Result.UNCHANGED;
        }
        supplier.setName(limit(data.name(), 255));
        supplier.setOdooWriteDate(data.odooWriteDate());
        copy(data, supplier);
        return Result.UPDATED;
    }

    @Transactional(readOnly = true)
    public Optional<Supplier> findByOdooPartnerId(long odooPartnerId) {
        return supplierRepository.findByOdooPartnerId(odooPartnerId);
    }

    /** Which of these Odoo partner ids FinBridge does not hold yet. */
    @Transactional(readOnly = true)
    public Set<Long> missingOdooPartnerIds(Collection<Long> odooPartnerIds) {
        Set<Long> missing = new HashSet<>(odooPartnerIds);
        if (!missing.isEmpty()) {
            supplierRepository.findByOdooPartnerIdIn(missing)
                    .forEach(supplier -> missing.remove(supplier.getOdooPartnerId()));
        }
        return missing;
    }

    private static void copy(SupplierSyncData data, Supplier supplier) {
        supplier.setTaxId(limit(data.taxId(), 64));
        supplier.setEmail(limit(data.email(), 190));
        supplier.setPhone(limit(data.phone(), 40));
        String country = data.countryCode();
        supplier.setCountryCode(country != null && country.length() == 2 ? country.toUpperCase(Locale.ROOT) : null);
        supplier.setActive(data.active());
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
