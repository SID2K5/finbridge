# Build log: what was run, when, and what came out

This is the dated evidence for each phase, on one developer machine (Windows 11, JDK 21.0.13,
Maven 3.9.9, Docker 29.9, the official `odoo:19.0` image, MySQL 8.4). It records real runs. It is
not regenerated; the repeatable checks are `docs/test-report.md` and `scripts/check_docs.py`.

## What has never been done

| Item | State |
| --- | --- |
| AWS deployment | **Never run.** Templates pass `cfn-lint` and scripts pass a syntax check; nothing was created in any AWS account |
| The SoapUI project in SoapUI | **Not opened.** Its ten envelopes were sent to the simulator by `scripts/check_docs.py` |
| The twelve required cases of the project brief | The list in `docs/test-report.md` was **derived from the design document**; the brief's own list was not at hand |
| A sync of hand-made vendors and bills | Only `[TEST]` records created by script were synchronized |
| The demo script, rehearsed start to finish | Written from the runs below; not rehearsed as one sitting |

## Phase 3: synchronization from a real Odoo (13 November 2026)

Real Odoo 19, real MySQL, backend 0.3.0.

| Run | Request | Status | Suppliers | Created | Updated | Unchanged | Rejected |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | first sync | COMPLETED | 3 | 4 | 0 | 0 | 0 |
| 2 | `{"fullResync": true}` | COMPLETED | 0 | 0 | 0 | 4 | 0 |
| 3 | after changing the reference of `BILL/2026/11/0002` in Odoo | COMPLETED | 0 | 0 | 1 | 0 | 0 |

After run 3 MySQL held 9 invoices with 9 distinct Odoo ids (4 from Odoo, 5 `[DEMO]` seed rows), the
draft bill was absent, the changed bill showed its new reference with sync status `UPDATED`, and
`integration_logs` held 7 ODOO rows, all `SUCCESS`. `OdooLiveIT` passed 3 of 3 against a live Odoo
container with a throwaway database.

## Phase 4: payments end to end (13 November 2026)

Backend 0.4.0 and simulator 0.4.0 as separate processes, dev MySQL, invoices from Odoo.

| Invoice | Scenario | What happened | At the simulator |
| --- | --- | --- | --- |
| `BILL/2026/11/0001` | SUCCESS | `SUCCESS`; same key again replayed it (`Idempotent-Replay: true`); a new key got `409` | 1 payment, 1 submission |
| `BILL/2026/11/0002` | SLOW_SUCCESS | `TIMEOUT_UNKNOWN` after 10.2 s, invoice stayed in progress; retry asked first and got `SUCCESS`, still attempt 1 | 1 payment, 1 submission: no duplicate |
| `BILL/2026/11/0003` | FAILED, then cleared | `FAILED`; operator retry gave `SUCCESS` as attempt 2 | 1 payment, 2 submissions |
| `BILL/2026/11/0004` | PENDING_THEN_SUCCESS | `PENDING`, still `PENDING` after check 1, `SUCCESS` after check 2 | 1 payment |
| `DEMO/BILL/2026/0001` | REJECTED | `REJECTED`; retry refused with `422` | 1 payment |
| `DEMO/BILL/2026/0002` | OUTAGE | `FAILED` with `SERVICE_UNAVAILABLE` | nothing recorded |
| `DEMO/BILL/2026/0003` | SHORT_PAY | `SUCCESS`, requested 99120.00, processed 89208.00 | 1 payment |

`integration_logs` then held 12 SOAP rows, each with its request and response XML.

## Phase 5: reconciliation (13 November 2026)

Backend 0.5.0; five new `[TEST]` bills synchronized, four paid, one orphan created at the simulator.
13 records, 1 matched, 12 discrepancies: all six statuses.

| Status | Records | What they were |
| --- | --- | --- |
| `MATCHED` | 1 | `BILL/2026/11/0005`, paid in full |
| `MISMATCH` | 1 | `BILL/2026/11/0006`: requested 40000, settled 36000 |
| `PENDING_REVIEW` | 1 | `BILL/2026/11/0007`, `PENDING` on both sides |
| `DUPLICATE` | 2 | `BILL/2026/11/0008` and `BILL/2026/11/0009`: same vendor, same reference `TEST-DUP-777` |
| `MISSING_INTERNAL_RECORD` | 1 | the orphan settlement of 48500 INR |
| `MISSING_EXTERNAL_RECORD` | 7 | the Phase 4 payments: the simulator had been restarted |

A review without a note got `400`; with a note the record became `NEEDS_INVESTIGATION` and the
invoice was unchanged.

## Phase 6: dashboard walkthrough (13 November 2026)

A real browser against backend 0.6.0, the simulator and the dev MySQL.

- **Administrator:** all twelve routes loaded with live data and no console errors; paid
  `DEMO/BILL/2026/0004` through the Pay dialog; read its SOAP request and response; opened a
  reconciliation record, a log entry, Users and Settings.
- **Finance Manager:** Finance pages only; `/settings` and `/integrations/logs` refused without an
  API call; no Check status, Retry or SOAP viewer on a payment.
- **Integration Operator:** no Pay button; `/reconciliation` refused; retried the failed payment of
  `DEMO/BILL/2026/0002` and it succeeded as attempt 2.

Not clicked through in a browser: starting a sync, running a reconciliation, saving a review,
creating a user, changing a setting, and the Retry button in the log drawer (added in Phase 7).
The API behind each is covered by integration tests.

## Phase 7: tests and hardening (14 November 2026)

214 tests, 211 passed, 0 failed, 3 skipped (`OdooLiveIT`, which needs a live Odoo and its key).
Backend coverage 91 % of lines, 72 % of branches. Six security findings fixed, each with a test;
the concurrency test was confirmed to fail without its fix. See `test-report.md` and
`security-review.md`.

## Phase 8: containers (14 and 15 November 2026)

All six containers healthy from `docker compose --profile app up -d --build`. Fifteen checks through
the nginx container passed (security headers, deep links, login, health check to Odoo, SOAP and REST
by service name, a sync, a payment). The dashboard loaded in a browser under the Content-Security-Policy
with no console errors. The backend image with the `prod` profile started on a fresh MySQL with TLS
required. AWS: see the table at the top.

## Phase 9: documents against the running system (15 November 2026)

`python scripts/check_docs.py` against the stack in containers (backend 0.7.0 on 8081, simulator
0.4.0 on 8090):

| Group | Checks | Result |
| --- | ---: | --- |
| Contract: XSD copies identical, WSDL names as in `soap.md`, WSDL copy current | 9 | passed |
| SoapUI project: each of its 10 envelopes gives the documented outcome | 11 | passed |
| Postman collection: each of its 37 requests gives the expected HTTP status | 37 | passed |
| `api.md`: role column (158 calls against 41 operations), operation list equals the live OpenAPI (42) | 2 | passed |
| `database.md`: equals the 12 tables of the live schema | 1 | passed |
| Links: every file named in the README and in `docs/*.md` exists | 11 | passed |

71 checks, 71 passed. Two runs make up this result. The first, before the README was rewritten,
had 69 checks and one failure (the old README named a file that does not exist); in it all 37
Postman requests were sent, including the payment. In the final run of 71 no open invoice was
left, so the seven Postman requests that act on a new payment were skipped and are counted as
passed on the strength of the first run.

Newman 6.2.3 then ran the collection itself, JavaScript tests included. The first run failed: the
two polling requests had the same name, so the jump that repeats a poll landed in the wrong folder
and 14 requests were never sent. The script replay above could not see this, because it does not
jump by name. After renaming them: 34 requests sent (30 distinct, 4 repeated polls), 53 assertions,
0 failed, but the seven payment requests were skipped by the collection's own guard, because no
open invoice was left. A `[TEST]` bill was then posted in Odoo (`BILL/2026/11/0011`) and the
collection run once more, whole: **38 requests, 63 assertions, 0 failed, nothing skipped.**

These runs paid every open invoice in the dev database, including the two `[TEST]` bills posted for
them. It now holds 16 invoices (14 paid, 1 payment failed, 1 in progress) and 16 payments
(14 `SUCCESS`, 1 `PENDING`, 1 `REJECTED`), and **no open invoice at all**: a demo, and a run of the
Postman collection's payment folder, both need new bills posted in Odoo first (`demo-script.md`).
