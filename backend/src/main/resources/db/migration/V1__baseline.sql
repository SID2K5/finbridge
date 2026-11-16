-- FinBridge baseline schema (MySQL 8.x, InnoDB, utf8mb4).
-- Money is DECIMAL(19,4). Timestamps are DATETIME(3) holding UTC.
-- Status columns are VARCHAR guarded by CHECK constraints and by Java enums.

CREATE TABLE roles (
    id   SMALLINT    NOT NULL,
    name VARCHAR(40) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name CHECK (name IN ('ADMIN', 'FINANCE_MANAGER', 'INTEGRATION_OPERATOR'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO roles (id, name) VALUES
    (1, 'ADMIN'),
    (2, 'FINANCE_MANAGER'),
    (3, 'INTEGRATION_OPERATOR');

CREATE TABLE users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(190) NOT NULL,
    full_name          VARCHAR(120) NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME(3)  NULL,
    last_login_at      DATETIME(3)  NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_roles (
    user_id BIGINT   NOT NULL,
    role_id SMALLINT NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE suppliers (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    odoo_partner_id BIGINT       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    tax_id          VARCHAR(64)  NULL,
    email           VARCHAR(190) NULL,
    phone           VARCHAR(40)  NULL,
    country_code    CHAR(2)      NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    odoo_write_date DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_suppliers_odoo_partner UNIQUE (odoo_partner_id),
    INDEX ix_suppliers_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE invoices (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    odoo_move_id       BIGINT         NOT NULL,
    invoice_number     VARCHAR(64)    NOT NULL,
    vendor_reference   VARCHAR(128)   NULL,
    po_reference       VARCHAR(64)    NULL,
    supplier_id        BIGINT         NOT NULL,
    invoice_date       DATE           NOT NULL,
    due_date           DATE           NULL,
    untaxed_amount     DECIMAL(19, 4) NOT NULL,
    tax_amount         DECIMAL(19, 4) NOT NULL,
    total_amount       DECIMAL(19, 4) NOT NULL,
    currency           CHAR(3)        NOT NULL,
    invoice_status     VARCHAR(24)    NOT NULL,
    odoo_payment_state VARCHAR(24)    NULL,
    sync_status        VARCHAR(24)    NOT NULL,
    odoo_write_date    DATETIME(3)    NOT NULL,
    last_synced_at     DATETIME(3)    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_invoices PRIMARY KEY (id),
    CONSTRAINT uq_invoices_odoo_move UNIQUE (odoo_move_id),
    CONSTRAINT fk_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT ck_invoices_total CHECK (total_amount >= 0),
    CONSTRAINT ck_invoices_status CHECK (invoice_status IN ('OPEN', 'PAYMENT_IN_PROGRESS', 'PAID', 'PAYMENT_FAILED')),
    CONSTRAINT ck_invoices_sync_status CHECK (sync_status IN ('SYNCED', 'UPDATED', 'STALE')),
    INDEX ix_invoices_number (invoice_number),
    INDEX ix_invoices_supplier_date (supplier_id, invoice_date),
    INDEX ix_invoices_status (invoice_status),
    INDEX ix_invoices_due_date (due_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_transactions (
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    transaction_reference   VARCHAR(40)    NOT NULL,
    idempotency_key         VARCHAR(64)    NOT NULL,
    request_hash            CHAR(64)       NOT NULL,
    invoice_id              BIGINT         NOT NULL,
    supplier_id             BIGINT         NOT NULL,
    requested_amount        DECIMAL(19, 4) NOT NULL,
    currency                CHAR(3)        NOT NULL,
    status                  VARCHAR(24)    NOT NULL,
    external_transaction_id VARCHAR(64)    NULL,
    processed_amount        DECIMAL(19, 4) NULL,
    failure_reason          VARCHAR(255)   NULL,
    attempt_count           INT            NOT NULL DEFAULT 0,
    requested_at            DATETIME(3)    NOT NULL,
    last_attempt_at         DATETIME(3)    NULL,
    responded_at            DATETIME(3)    NULL,
    initiated_by            BIGINT         NOT NULL,
    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_payment_transactions PRIMARY KEY (id),
    CONSTRAINT uq_payment_tx_reference UNIQUE (transaction_reference),
    CONSTRAINT uq_payment_tx_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_tx_external UNIQUE (external_transaction_id),
    CONSTRAINT fk_payment_tx_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_payment_tx_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_payment_tx_user FOREIGN KEY (initiated_by) REFERENCES users (id),
    CONSTRAINT ck_payment_tx_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_payment_tx_status CHECK (status IN
        ('CREATED', 'SENT', 'SUCCESS', 'PENDING', 'FAILED', 'REJECTED', 'TIMEOUT_UNKNOWN')),
    INDEX ix_payment_tx_invoice (invoice_id),
    INDEX ix_payment_tx_status_requested (status, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE integration_logs (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    correlation_id         CHAR(36)      NOT NULL,
    integration_type       VARCHAR(8)    NOT NULL,
    operation              VARCHAR(64)   NOT NULL,
    request_reference      VARCHAR(64)   NULL,
    outcome                VARCHAR(16)   NOT NULL,
    http_status            SMALLINT      NULL,
    duration_ms            INT           NOT NULL,
    error_code             VARCHAR(64)   NULL,
    error_description      VARCHAR(1000) NULL,
    invoice_id             BIGINT        NULL,
    payment_transaction_id BIGINT        NULL,
    request_payload        MEDIUMTEXT    NULL,
    response_payload       MEDIUMTEXT    NULL,
    retryable              BOOLEAN       NOT NULL DEFAULT FALSE,
    retry_of_log_id        BIGINT        NULL,
    created_at             DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_integration_logs PRIMARY KEY (id),
    CONSTRAINT fk_int_logs_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_int_logs_payment FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions (id),
    CONSTRAINT fk_int_logs_retry_of FOREIGN KEY (retry_of_log_id) REFERENCES integration_logs (id),
    CONSTRAINT ck_int_logs_type CHECK (integration_type IN ('ODOO', 'REST', 'SOAP')),
    CONSTRAINT ck_int_logs_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'TIMEOUT')),
    INDEX ix_int_logs_type_created (integration_type, created_at),
    INDEX ix_int_logs_outcome_created (outcome, created_at),
    INDEX ix_int_logs_correlation (correlation_id),
    INDEX ix_int_logs_reference (request_reference)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sync_runs (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    trigger_type       VARCHAR(16)   NOT NULL,
    triggered_by       BIGINT        NULL,
    status             VARCHAR(16)   NOT NULL,
    cursor_from        DATETIME(3)   NULL,
    cursor_to          DATETIME(3)   NULL,
    suppliers_upserted INT           NOT NULL DEFAULT 0,
    invoices_created   INT           NOT NULL DEFAULT 0,
    invoices_updated   INT           NOT NULL DEFAULT 0,
    invoices_unchanged INT           NOT NULL DEFAULT 0,
    invoices_rejected  INT           NOT NULL DEFAULT 0,
    error_message      VARCHAR(1000) NULL,
    started_at         DATETIME(3)   NOT NULL,
    finished_at        DATETIME(3)   NULL,
    CONSTRAINT pk_sync_runs PRIMARY KEY (id),
    CONSTRAINT fk_sync_runs_user FOREIGN KEY (triggered_by) REFERENCES users (id),
    CONSTRAINT ck_sync_runs_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT ck_sync_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    INDEX ix_sync_runs_status_started (status, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE synchronization_history (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    sync_run_id    BIGINT       NOT NULL,
    entity_type    VARCHAR(16)  NOT NULL,
    odoo_record_id BIGINT       NOT NULL,
    invoice_id     BIGINT       NULL,
    action         VARCHAR(24)  NOT NULL,
    message        VARCHAR(500) NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_synchronization_history PRIMARY KEY (id),
    CONSTRAINT fk_sync_hist_run FOREIGN KEY (sync_run_id) REFERENCES sync_runs (id),
    CONSTRAINT fk_sync_hist_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT ck_sync_hist_entity CHECK (entity_type IN ('SUPPLIER', 'INVOICE')),
    CONSTRAINT ck_sync_hist_action CHECK (action IN
        ('CREATED', 'UPDATED', 'UNCHANGED', 'REJECTED_INVALID', 'FAILED')),
    INDEX ix_sync_hist_invoice_created (invoice_id, created_at),
    INDEX ix_sync_hist_run (sync_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reconciliation_runs (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    triggered_by      BIGINT      NOT NULL,
    period_from       DATE        NOT NULL,
    period_to         DATE        NOT NULL,
    status            VARCHAR(16) NOT NULL,
    total_records     INT         NOT NULL DEFAULT 0,
    matched_count     INT         NOT NULL DEFAULT 0,
    discrepancy_count INT         NOT NULL DEFAULT 0,
    started_at        DATETIME(3) NOT NULL,
    finished_at       DATETIME(3) NULL,
    CONSTRAINT pk_reconciliation_runs PRIMARY KEY (id),
    CONSTRAINT fk_recon_runs_user FOREIGN KEY (triggered_by) REFERENCES users (id),
    CONSTRAINT ck_recon_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_recon_runs_period CHECK (period_from <= period_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reconciliation_records (
    id                             BIGINT         NOT NULL AUTO_INCREMENT,
    reconciliation_run_id          BIGINT         NOT NULL,
    invoice_id                     BIGINT         NULL,
    payment_transaction_id         BIGINT         NULL,
    internal_transaction_reference VARCHAR(40)    NULL,
    external_transaction_reference VARCHAR(64)    NULL,
    expected_amount                DECIMAL(19, 4) NULL,
    actual_amount                  DECIMAL(19, 4) NULL,
    currency                       CHAR(3)        NULL,
    status                         VARCHAR(32)    NOT NULL,
    discrepancy_description        VARCHAR(1000)  NULL,
    review_status                  VARCHAR(24)    NOT NULL,
    review_note                    VARCHAR(1000)  NULL,
    reviewed_by                    BIGINT         NULL,
    reviewed_at                    DATETIME(3)    NULL,
    reconciled_at                  DATETIME(3)    NOT NULL,
    CONSTRAINT pk_reconciliation_records PRIMARY KEY (id),
    CONSTRAINT fk_recon_rec_run FOREIGN KEY (reconciliation_run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT fk_recon_rec_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_recon_rec_payment FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions (id),
    CONSTRAINT fk_recon_rec_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT ck_recon_rec_status CHECK (status IN
        ('MATCHED', 'MISMATCH', 'MISSING_EXTERNAL_RECORD', 'MISSING_INTERNAL_RECORD', 'DUPLICATE', 'PENDING_REVIEW')),
    CONSTRAINT ck_recon_rec_review CHECK (review_status IN
        ('NOT_REQUIRED', 'OPEN', 'RESOLVED', 'NEEDS_INVESTIGATION')),
    INDEX ix_recon_rec_run_status (reconciliation_run_id, status),
    INDEX ix_recon_rec_invoice (invoice_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Non-secret settings only. API keys, passwords and the JWT secret stay in the environment.
CREATE TABLE integration_configurations (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    config_key   VARCHAR(100) NOT NULL,
    config_value VARCHAR(500) NOT NULL,
    description  VARCHAR(255) NULL,
    updated_by   BIGINT       NULL,
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_integration_configurations PRIMARY KEY (id),
    CONSTRAINT uq_int_config_key UNIQUE (config_key),
    CONSTRAINT fk_int_config_user FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
