package com.finbridge.supplier;

import java.time.Instant;

/** A supplier as delivered by a synchronization, independent of where it came from. */
public record SupplierSyncData(
        long odooPartnerId,
        String name,
        String taxId,
        String email,
        String phone,
        String countryCode,
        boolean active,
        Instant odooWriteDate) {
}
