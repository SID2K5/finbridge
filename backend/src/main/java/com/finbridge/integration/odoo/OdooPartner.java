package com.finbridge.integration.odoo;

import java.time.Instant;

/** A res.partner row as FinBridge needs it. Optional values are null, never Odoo's "false". */
public record OdooPartner(
        long id,
        String name,
        String vat,
        String email,
        String phone,
        String countryCode,
        boolean active,
        Instant writeDate) {
}
