-- DEMO DATA. Loaded only under the "dev" Spring profile (see application-dev.yml).
-- Every row is labelled [DEMO] and uses Odoo ids from 900001 upward so it can never
-- collide with records synchronized from a real Odoo database in Phase 3.
-- Repeatable migration: safe to re-run, it never overwrites existing rows.

INSERT IGNORE INTO suppliers
    (odoo_partner_id, name, tax_id, email, phone, country_code, active, odoo_write_date)
VALUES
    (900001, '[DEMO] Apex Industrial Supplies', 'DEMO-TAX-0001', 'accounts@apex-demo.example', '+91 00000 00001', 'IN', TRUE, '2026-10-01 09:00:00.000'),
    (900002, '[DEMO] Brightline Logistics',     'DEMO-TAX-0002', 'billing@brightline-demo.example', '+91 00000 00002', 'IN', TRUE, '2026-10-01 09:00:00.000'),
    (900003, '[DEMO] Cobalt Office Systems',    'DEMO-TAX-0003', 'finance@cobalt-demo.example', '+91 00000 00003', 'IN', TRUE, '2026-10-01 09:00:00.000');

INSERT IGNORE INTO invoices
    (odoo_move_id, invoice_number, vendor_reference, po_reference, supplier_id,
     invoice_date, due_date, untaxed_amount, tax_amount, total_amount, currency,
     invoice_status, odoo_payment_state, sync_status, odoo_write_date, last_synced_at)
SELECT v.odoo_move_id, v.invoice_number, v.vendor_reference, v.po_reference, s.id,
       v.invoice_date, v.due_date, v.untaxed_amount, v.tax_amount, v.total_amount, 'INR',
       'OPEN', 'not_paid', 'SYNCED', '2026-10-20 10:00:00.000', '2026-10-20 10:05:00.000'
FROM (
    SELECT 900001 AS odoo_move_id, 'DEMO/BILL/2026/0001' AS invoice_number, 'APX-7781' AS vendor_reference,
           'DEMO-P00001' AS po_reference, 900001 AS odoo_partner_id, DATE '2026-10-05' AS invoice_date,
           DATE '2026-11-04' AS due_date, 50000.0000 AS untaxed_amount, 9000.0000 AS tax_amount, 59000.0000 AS total_amount
    UNION ALL SELECT 900002, 'DEMO/BILL/2026/0002', 'APX-7790', 'DEMO-P00002', 900001, DATE '2026-10-12', DATE '2026-11-11', 12500.0000, 2250.0000, 14750.0000
    UNION ALL SELECT 900003, 'DEMO/BILL/2026/0003', 'BL-2231',  'DEMO-P00003', 900002, DATE '2026-10-14', DATE '2026-11-13', 84000.0000, 15120.0000, 99120.0000
    UNION ALL SELECT 900004, 'DEMO/BILL/2026/0004', 'BL-2260',  NULL,          900002, DATE '2026-10-18', DATE '2026-11-17', 7200.0000, 1296.0000, 8496.0000
    UNION ALL SELECT 900005, 'DEMO/BILL/2026/0005', 'COS-0098', 'DEMO-P00004', 900003, DATE '2026-10-19', DATE '2026-11-18', 23000.0000, 4140.0000, 27140.0000
) AS v
JOIN suppliers s ON s.odoo_partner_id = v.odoo_partner_id;
