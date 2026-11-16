package com.finbridge.supplier;

import java.time.Instant;

public record SupplierResponse(
        Long id,
        Long odooPartnerId,
        String name,
        String taxId,
        String email,
        String phone,
        String countryCode,
        boolean active,
        Instant odooWriteDate,
        Instant updatedAt) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getOdooPartnerId(), supplier.getName(),
                supplier.getTaxId(), supplier.getEmail(), supplier.getPhone(), supplier.getCountryCode(),
                supplier.isActive(), supplier.getOdooWriteDate(), supplier.getUpdatedAt());
    }
}
