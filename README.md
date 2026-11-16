# FinBridge

A finance systems integration platform. FinBridge reads posted vendor bills from an **Odoo 19** ERP,
pays them through an external payment service over **SOAP**, reconciles what it paid against that
service's settlement statement, and shows all of it in a role-based **React** dashboard backed by a
**Spring Boot** REST API and **MySQL**.

| | |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring Security (JWT), JPA, Flyway, Spring WS client |
| Payment simulator | A second Spring Boot application: SOAP 1.1 server, contract-first, seven failure scenarios |
| Frontend | React 18, TypeScript, Vite, Tailwind, Recharts; nginx in the image |
| Data | MySQL 8.4 (12 tables); Odoo 19 Community with its own PostgreSQL 16 |
| Packaging | Docker Compose locally; CloudFormation for AWS (ECS Fargate, RDS, ALB, EC2 for Odoo) |

## Status

Checked on 15 November 2026.

| Area | State |
| --- | --- |
| Backend, simulator, frontend | Built and tested: 214 automated tests, 211 passed, 0 failed, 3 skipped (they need a live Odoo key). Backend coverage 91 % of lines |
| Odoo synchronization, payments, reconciliation | Each run end to end against a real Odoo 19, the simulator and MySQL (`docs/build-log.md`) |
| Whole stack in containers | Tested locally |
| Documentation | Checked against the running system by `scripts/check_docs.py` (results in `docs/build-log.md`) |
| AWS deployment | **The free-tier path is deployed and the three flows run in the cloud** (16 November 2026, `ap-southeast-2`, one `t3.small`): a synchronization brought 6 bills from Odoo, a payment succeeded over SOAP, and reconciliation found the duplicate pair. The Fargate design in `deployment-guide.md` has never been run |
| Postman collection | Run whole with newman: 38 requests, 63 assertions, 0 failed, nothing skipped |
| SoapUI project | Every envelope was sent by script and gave the documented outcome. **Not opened in SoapUI itself**, which is not installed on the build machine |
| The twelve required test cases | Derived from the design document, because the brief's own list was not at hand. **Compare `docs/test-report.md` with your brief — this is the one open item nobody but you can close** |
| The dashboard in the cloud | Every route verified in a browser against the deployed instance, with live data and no console errors |
| Reconciliation statuses | All six produced on the deployed system |

## Documentation

| Document | What it answers |
| --- | --- |
| [docs/project-brief.md](docs/project-brief.md) | **Start here.** The whole project in one document: what it is, how to run it, what to demonstrate, and how to explain it |
| [docs/architecture.md](docs/architecture.md) | Components, modules, the three flows, roles, differences from the design, known limits |
| [docs/api.md](docs/api.md) | Every REST endpoint with its roles (generated; the machine-readable form is `docs/reference/openapi.json`) |
| [docs/database.md](docs/database.md) | Every table, column, key and constraint, with an ER diagram (generated from the live schema) |
| [docs/odoo.md](docs/odoo.md) | The Odoo API used, field mapping, synchronization rules, setup, renewing the API key |
| [docs/soap.md](docs/soap.md) | The SOAP contract, faults, failure handling, idempotency, simulator scenarios and controls |
| [docs/free-tier-deployment.md](docs/free-tier-deployment.md) | Deploying on a free AWS account: one EC2 instance, what it costs, start and stop |
| [docs/deployment-guide.md](docs/deployment-guide.md) | The production-shaped AWS design (Fargate, load balancer, RDS), with costs and teardown |
| [docs/security-review.md](docs/security-review.md) | What was reviewed, what was fixed, what was knowingly left |
| [docs/test-report.md](docs/test-report.md) | Test results, the required cases mapped to tests, coverage (generated) |
| [docs/build-log.md](docs/build-log.md) | Dated results of the real runs, and the list of what has never been done |
| [docs/demo-script.md](docs/demo-script.md) | A 12-minute walkthrough |
| `postman/`, `soapui/` | A Postman collection (37 requests) with its environment, and a SoapUI project (10 requests) |

## Quick start: everything in containers

You need Docker Desktop, running.

1. Create your settings file from the template and replace every `change-me` value. The comments in
   `.env.example` say what each one needs (passwords of 12 to 72 characters, a JWT secret of at
   least 32). `.env` is git-ignored; never commit it.

   ```
   cp .env.example .env
   ```

2. Build and start. The first build takes several minutes.

   ```
   docker compose --profile app up -d --build
   ```

3. Wait until `docker compose ps` shows every container `healthy`, then open:

   | Address | What |
   | --- | --- |
   | http://localhost:8088 | The dashboard. Sign in with `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` |
   | http://localhost:8081/swagger-ui.html | The backend's API console (dev profile) |
   | http://localhost:8090/ws/payments.wsdl | The payment simulator's WSDL |
   | http://localhost:8069 | Odoo |

4. Odoo needs a one-time setup (a database, an integration user and an API key in `.env` as
   `ODOO_API_KEY`): see [docs/odoo.md](docs/odoo.md). Without Odoo, the backend can run with a
   clearly labelled mock: set `APP_PROFILE=dev,mock-odoo` in `.env`.

Ports can be changed with `FRONTEND_PORT`, `BACKEND_PORT`, `SIM_PORT` and `DB_PORT` in `.env`.

## Development setup

You need JDK 21, Maven 3.9 or later, Node.js 20 or later, and Docker Desktop. Three terminals, all
reading the same `.env`:

```
docker compose up -d mysql odoo
```

```
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

```
cd payment-simulator
mvn spring-boot:run
```

```
cd frontend
npm install
npm run dev
```

The dashboard is then at http://localhost:5173 and the backend at http://localhost:8080. The dev
server forwards `/api` to the backend; if the backend runs on another port, copy
`frontend/.env.example` to a new file named `.env.local` in the same folder and set
`VITE_BACKEND_URL` there.

## Using it

| Step | Where | Role |
| --- | --- | --- |
| Check the connections | Integrations > Run health check | Admin, Operator |
| Bring in bills | Invoices > Sync from Odoo. Only posted vendor bills are read | Admin, Operator |
| Pay a bill | Open an invoice > Pay | Admin, Finance Manager |
| Resolve a payment that is pending or of unknown outcome | Open the payment > Check status, or Retry | Admin, Operator |
| Reconcile | Reconciliation > Run reconciliation, then review each discrepancy with a note | Admin, Finance Manager |
| See every outbound call | Integrations > Logs | Admin, Operator |
| Users and settings | Users, Settings | Admin |

To make a payment fail in a chosen way, set a simulator scenario for its invoice number first:
[docs/soap.md](docs/soap.md). The same journey over the API is the Postman collection; import both
files in `postman/` and fill in `adminPassword` and `simApiKey` in the environment.

## Tests

```
cd backend
mvn clean verify
```

```
cd payment-simulator
mvn clean verify
```

```
cd frontend
npx vitest run --reporter=json --outputFile=test-results.json
```

`mvn verify` runs the integration tests against a real MySQL 8.4 that Testcontainers starts and
removes, so Docker must be running. Then, from the `finbridge` folder:

| Command | Does |
| --- | --- |
| `python scripts/test_report.py` | Rebuilds `docs/test-report.md` from the result files; fails if any test failed |
| `python scripts/api_reference.py generate` | Rebuilds `docs/api.md` and `docs/reference/openapi.json` from the running backend |
| `python scripts/db_reference.py` | Rebuilds `docs/database.md` from the running MySQL |
| `python scripts/check_docs.py` | Checks the documents, the Postman collection and the SoapUI project against the running stack. It pays one open invoice and starts one sync and one reconciliation run |

## Profiles

| Profile | Demo data | Swagger UI | Logs |
| --- | --- | --- | --- |
| none (default) | No | Needs a token | Text |
| `dev` | Rows labelled `[DEMO]` | Open | Text |
| `prod` | No | Needs a token | JSON |
| `mock-odoo` (added to another) | Replaces the real Odoo adapter with the mock; everything it produces is labelled `[MOCK]` | | |

The default is the secure one on purpose. `prod` refuses to start on a database that holds the
demo data.

## Stopping

```
docker compose --profile app stop backend simulator frontend
```

```
docker compose stop
```

`docker compose down -v` deletes the containers **and every volume**: the MySQL data and the whole
Odoo database, including its integration user and API key.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `Could not resolve placeholder 'DB_URL'` | `.env` is missing, or the command was not run from `finbridge/` or one of its application folders |
| `JWT_SECRET must be at least 32 characters` | Lengthen `JWT_SECRET` in `.env` |
| `Access denied for user 'finbridge'` | The MySQL volume was created with an older password. `docker compose down -v` (this deletes the data), then start again |
| `release version 21 not supported` | Maven is running on an older JDK. Point `JAVA_HOME` at JDK 21 (`mvn -version` shows which one it uses) |
| Port 8080, 3306 or another is already in use | Change the matching `..._PORT` in `.env` |
| Sync refused with `Odoo is not configured` | `ODOO_API_KEY` is empty in `.env`, or the backend was not restarted after setting it |
| Health check `ODOO_AUTH` | Wrong or expired key (Odoo keys last three months at most), or `ODOO_DB` is not the database name |
| Health check `ODOO_UNREACHABLE` | Odoo is not running, or `ODOO_URL` is wrong |
| Health check `soap` or `rest` not ok | The simulator is not running, or `SIM_API_KEY` differs between the two applications |
| A payment stays `TIMEOUT_UNKNOWN` | Correct until someone asks: open the payment and press Check status |
| Paid invoices show as `MISSING_EXTERNAL_RECORD` | The simulator was restarted and has forgotten them |
| Integration tests: `Could not find a valid Docker environment` | Docker Desktop is not running |

## Known limitations

The full lists are in [docs/architecture.md](docs/architecture.md) (Known limits),
[docs/odoo.md](docs/odoo.md) and [docs/soap.md](docs/soap.md) (Limits) and
[docs/security-review.md](docs/security-review.md). The ones that matter most:

- Never deployed to AWS; the cloud demo would run over plain HTTP.
- FinBridge never writes to Odoo, so a bill paid here stays unpaid there.
- Payments are for the full invoice total. No partial payments, no refunds.
- Synchronization and reconciliation are started by hand, and a payment of unknown outcome stays
  that way until someone checks it. Nothing is scheduled.
- One backend instance is assumed.
- The simulator keeps its state in memory and has no authentication on its SOAP endpoint.
- Each reconciliation run stands alone: a discrepancy resolved in one run is open again in the next
  run over the same period. Nothing stops you paying a duplicate bill; reconciliation reports it.
- The frontend has unit tests for its logic only; pages were checked by hand, and five actions
  were never clicked through in a browser (`docs/build-log.md`, Phase 6).
- `npm audit` reports two moderate advisories in react-router 6 whose fix needs a major upgrade;
  Maven dependencies have not been scanned.
- Spring Boot 3.5 has been out of open-source support since 30 June 2026.
