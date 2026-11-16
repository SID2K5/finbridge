# FinBridge: the whole project in one document

Everything you need to run it, show it and talk about it. The other documents go deeper; this one
is the map. Written 16 November 2026.

---

## 1. What it is, in one paragraph

FinBridge is a finance systems integration platform. It reads posted vendor bills from an **Odoo 19
ERP**, lets a finance manager pay them through an **external payment service that speaks SOAP**, and
then **reconciles** its own records against that service's settlement statement, reporting every
discrepancy for a human to review. A role-based React dashboard sits on top. The point of the
system is not any one of those features — it is that three different integration styles, each with
its own failure modes, have to agree about money.

## 2. The problem it solves

A company's ERP knows what it owes. The bank or payment provider knows what it actually paid. In
most small finance departments nobody systematically compares the two, so short payments, duplicate
bills and payments that silently failed are found weeks later, by accident. FinBridge closes that
loop: it takes the bills from the ERP, sends the payments itself, and then checks both sides
against each other and says exactly where they disagree.

## 3. Architecture at a glance

```
          Odoo 19 (ERP)                    Payment service (SOAP)
          vendors, bills                   submit / status / statement
                |                                    |
         JSON-2 over HTTP                        SOAP 1.1
          (bearer API key)                   (contract-first, XSD)
                |                                    |
                +------------ FinBridge backend -----+
                              Spring Boot 3.5, Java 21
                              MySQL 8.4, Flyway, JWT
                                        |
                                   REST /api
                                        |
                              React 18 dashboard (nginx)
```

| Piece | Technology | Role |
| --- | --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring Security, JPA, Flyway, Spring WS | Every business rule and every credential |
| Database | MySQL 8.4, 12 tables, 4 migrations | FinBridge's own records |
| ERP | Odoo 19 Community + PostgreSQL 16 | Source of suppliers and bills. **Read-only** to FinBridge |
| Payment service | A purpose-built SOAP simulator (Spring WS, in-memory) | Stands in for a bank. Can be told to fail in seven ways |
| Dashboard | React 18, TypeScript, Vite, Tailwind, Recharts; nginx | Twelve routes, role-aware |

It is a **modular monolith**: one deployable, organised by package, with `OdooGateway` and
`PaymentGateway` as ports so that the business code never sees JSON-2 or SOAP.

Full detail: [architecture.md](architecture.md).

## 4. The three integrations

| Style | Where | Why it is interesting |
| --- | --- | --- |
| **RPC over HTTP (JSON)** | Odoo's External JSON-2 API, `POST /json/2/<model>/<method>` with a bearer key | Paging, an incremental cursor, and upserts that must never duplicate a bill |
| **SOAP 1.1, contract-first** | The payment service: `SubmitPayment`, `GetPaymentStatus`, `GetSettlementStatement` | A hand-written XSD generates the WSDL and both sides' classes; both validate every message in both directions |
| **REST** | Browser to backend, and backend to the simulator's health endpoint | The product's own API: 42 operations, one response envelope, correlation id on everything |

Every outbound call writes a row to `integration_logs` with its duration, outcome, error code and
masked payloads, under the correlation id of the request that caused it.

## 5. The three things worth being proud of

These are what distinguish the project from a CRUD application, and what you should lead with.

**A payment whose answer is lost.** If the payment service does not reply within ten seconds,
FinBridge does **not** mark the payment failed — it records `TIMEOUT_UNKNOWN`, meaning *the request
left and we do not know*. The only way out is to ask the service (`GetPaymentStatus`). Treating a
timeout as a failure and retrying is how real systems pay twice.

**Double payment is impossible, even under concurrency.** Two unique keys and a row lock on the
invoice. A test fires eight threads at one invoice simultaneously and exactly one payment results.
This test failed before the fix: the duplicate check read a stale snapshot after taking the lock,
which the security review caught.

**Reconciliation that explains itself.** Six statuses decided by the first matching rule, each
record storing both sides as they were compared, so a finding stays explicable months later.
A mismatch reads: *"amount: FinBridge requested 33000, payment service settled 29700 (difference 3300)"*.

## 6. How to run it

### Locally, everything in containers

```bash
cd finbridge && cp .env.example .env
```

Fill in every `change-me` value, then:

```bash
docker compose --profile app up -d --build
```

Dashboard at http://localhost:8088, API console at http://localhost:8081/swagger-ui.html, Odoo at
http://localhost:8069.

### On AWS (already deployed)

```bash
cd finbridge/deploy/aws && AWS_REGION=ap-southeast-2 ./free.sh start
```

```bash
cd finbridge/deploy/aws && AWS_REGION=ap-southeast-2 ./free.sh status
```

The address changes on every start; `status` prints it. `./free.sh credentials` prints the
sign-ins, `./free.sh stop` ends the compute billing, `./free.sh teardown` deletes everything.
Details and costs: [free-tier-deployment.md](free-tier-deployment.md).

### The tests

```bash
cd finbridge/backend && mvn clean verify
```

Integration tests run against a real MySQL 8.4 that Testcontainers starts and removes, so Docker
must be running.

## 7. The operations to perform

In order. This is also the demo order, because it follows the money.

| # | Operation | Where | Role | What it proves |
| --- | --- | --- | --- | --- |
| 1 | **Health check** | Integrations | Admin, Operator | All three connections answer, with timings |
| 2 | **Sync from Odoo** | Invoices → Sync | Admin, Operator | Bills arrive; drafts never do |
| 3 | **Sync again** | Same | Same | 0 created — duplicate protection |
| 4 | **Pay an invoice** | Invoice → Pay | Admin, Finance | SOAP call out, `SUCCESS` back, invoice `PAID` |
| 5 | **Pay the same invoice again** | Same | Same | Refused — one payment per invoice |
| 6 | **Pay under `SLOW_SUCCESS`** | Invoice → Pay | Admin, Finance | `TIMEOUT_UNKNOWN` after 10 s |
| 7 | **Check status** | Payment → Check status | Admin, Operator | Resolves to `SUCCESS`, **still attempt 1** |
| 8 | **Retry a failed payment** | Payment → Retry | Admin, Operator | New attempt, new idempotency key |
| 9 | **Run reconciliation** | Reconciliation | Admin, Finance | Matches, mismatches, duplicates, orphans |
| 10 | **Review a discrepancy** | A record → review | Admin, Finance | Note required; changes no invoice or payment |
| 11 | **Read the integration log** | Integrations → Logs | Admin, Operator | Every call with its SOAP XML, masked |
| 12 | **Users and settings** | Admin pages | Admin | Roles, runtime settings, secrets never shown |

To make a payment misbehave, set a simulator scenario for its invoice number **before** paying:
`SUCCESS`, `PENDING_THEN_SUCCESS`, `FAILED`, `REJECTED`, `SLOW_SUCCESS`, `SHORT_PAY`, `OUTAGE`.
See [soap.md](soap.md).

## 8. How to explain it to someone

### The thirty-second version

> FinBridge sits between a company's ERP and its payment provider. It pulls in the bills that are
> due, pays them, and then checks what the provider actually did against what we asked for. It
> speaks three different protocols to do that, and it is built so that a payment can never be sent
> twice — including when the provider stops answering halfway through.

### The arc to tell, in order

1. **The business problem.** Nobody reconciles ERP against bank. Errors are found late.
2. **The shape of the solution.** One backend, three integrations, one dashboard.
3. **Show it synchronizing.** Bills appear. Sync again — nothing duplicates.
4. **Show a payment.** Simple, works.
5. **Now break it.** Set `SLOW_SUCCESS`. The payment times out. *Do not* mark it failed — ask.
   This is the heart of the demo. Pause here.
6. **Show reconciliation.** Short payment caught, duplicate bills caught, an orphan settlement
   caught. Review one with a note.
7. **Show the evidence.** Integration logs with the actual SOAP XML, masked.
8. **Close with the limits.** Say what it does not do, before you are asked.

### What to say at the hard moment

When the payment goes `TIMEOUT_UNKNOWN`, say this:

> The request left our system and the provider never answered. We genuinely do not know whether the
> money moved. The wrong thing here is to assume it failed and retry — that is how you pay twice.
> So we record it as unknown, and the only way out is to ask the provider what happened.

Then press Check status and show it resolve, **still on attempt 1**, with one payment at the
provider. That contrast is the whole argument.

### Questions you will be asked, and the honest answers

| Question | Answer |
| --- | --- |
| Why a simulator and not a real bank? | No sandbox was in scope. The contract is a real WSDL and both sides validate against it, so swapping in a real provider is an adapter change, not a redesign |
| How do you know payments can't double? | `PaymentHardeningIT`: eight threads pay one invoice at once, one payment results. It failed before the fix |
| What if Odoo is down? | Three attempts with backoff, then the run ends `FAILED` and the cursor does not move. Nothing is half-applied |
| Why a monolith? | One team, one deployable, transactional consistency across modules. The ports mean the integrations could be split out later |
| Is it deployed? | Yes — AWS, one EC2 instance, roughly $2 a month. All three flows have been run there, not just locally |
| What about security? | JWT, three roles enforced at URL and method level, BCrypt 12, account lockout, payload masking, XXE refused on both sides, CSP. A review found six issues; all six are fixed with tests |
| What would you do next? | HTTPS with a real domain, scheduled synchronization, partial payments, and writing the payment back to Odoo |

### Limits to state before you are asked

Saying these first makes you credible; being caught by them does the opposite.

- Plain HTTP in the cloud — no domain, so no certificate. Demo only.
- Payments are for the full invoice amount; no partial payments, no refunds.
- FinBridge never writes to Odoo, so the bill stays open there.
- Synchronization and reconciliation are started by hand; nothing is scheduled.
- The simulator keeps its state in memory and forgets everything on restart.
- One backend instance is assumed.

## 9. Numbers worth quoting

| | |
| --- | --- |
| Automated tests | **214** — 211 passed, 3 skipped (they need a live Odoo key) |
| Backend coverage | 91 % of lines, 72 % of branches |
| REST operations | 42, each with its roles verified against the running backend |
| Database | 12 tables, 4 Flyway migrations, `ddl-auto: validate` |
| Payment states | 7; failure scenarios 7; reconciliation statuses 6 |
| Security findings fixed | 6, each with a test that fails without the fix |
| Postman collection | 38 requests, 63 assertions, 0 failures under newman |
| Deployment | AWS `ap-southeast-2`, one `t3.small`, about $1.85/month stopped |

## 10. The rest of the documentation

| Document | For |
| --- | --- |
| [architecture.md](architecture.md) | Components, modules, flows, roles, known limits |
| [api.md](api.md) | Every endpoint and its roles (generated) |
| [database.md](database.md) | Every table and constraint, with an ER diagram (generated) |
| [odoo.md](odoo.md) | The Odoo API, field mapping, sync rules, API keys |
| [soap.md](soap.md) | The contract, faults, failure handling, idempotency, scenarios |
| [security-review.md](security-review.md) | What was reviewed, fixed, and knowingly left |
| [test-report.md](test-report.md) | Results, required cases mapped to tests, coverage |
| [free-tier-deployment.md](free-tier-deployment.md) | The AWS deployment, costs, and what it proved |
| [deployment-guide.md](deployment-guide.md) | The larger Fargate design (never run) |
| [demo-script.md](demo-script.md) | The 12-minute walkthrough, with cloud notes |
| [build-log.md](build-log.md) | Dated evidence of every real run |

## 11. The one thing still open

`test-report.md` maps twelve required test cases to the tests that prove them — but **that list was
derived from the design document**, because the project brief's own list was not available. Compare
it against your brief. If the brief names a case that is not there, a test may be missing. Nobody
else can close this item.
