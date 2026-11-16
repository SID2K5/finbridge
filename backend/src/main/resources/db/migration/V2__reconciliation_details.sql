-- Phase 5: what reconciliation needs beyond the baseline.
--
-- reconciliation_runs.error_message: why a run FAILED (for example the settlement statement
-- could not be fetched), so the reason is visible without reading the application log.
--
-- reconciliation_records.*_snapshot: the internal and the external side exactly as they were
-- compared, stored as small JSON objects. The record must stay explainable later, even after
-- the payment transaction has changed or the payment service has forgotten the payment.

ALTER TABLE reconciliation_runs
    ADD COLUMN error_message VARCHAR(1000) NULL AFTER discrepancy_count;

ALTER TABLE reconciliation_records
    ADD COLUMN internal_snapshot TEXT NULL AFTER discrepancy_description,
    ADD COLUMN external_snapshot TEXT NULL AFTER internal_snapshot;
