package com.finbridge.integration.odoo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The port between FinBridge and Odoo. Everything version-specific (model names, field
 * names, the JSON-2 wire format) lives behind it in an adapter, so the synchronization
 * code never sees Odoo's data model.
 *
 * Paging contract: records come back ordered by write date and then id, oldest first.
 * "since" is inclusive, so the record that set the cursor is read again on the next run and
 * reported as unchanged; nothing sharing that second is ever skipped.
 */
public interface OdooGateway {

    enum Mode { REAL, MOCK }

    Mode mode();

    /** False when a required setting (the API key) is missing; no call is attempted then. */
    boolean isConfigured();

    /** A cheap authenticated call. Throws OdooException if Odoo cannot be reached or refuses the key. */
    void ping();

    List<OdooPartner> fetchSuppliers(Instant since, int offset, int limit, String reference);

    /** Partners referenced by a bill that the supplier pass did not return (for example a contact with no supplier rank). */
    List<OdooPartner> fetchPartnersByIds(Collection<Long> ids, String reference);

    List<OdooVendorBill> fetchVendorBills(Instant since, int offset, int limit, String reference);
}
