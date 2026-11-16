# Demo script

About 12 minutes. It shows the three integrations, the one hard problem (a payment whose answer is
lost) and reconciliation finding real differences.

Every step below has been run, and every scenario was exercised against the **deployed** system on
16 November 2026 ([free-tier-deployment.md](free-tier-deployment.md) has the results). What has
never been done is the whole thing as one continuous performance in front of a clock, so allow
slack the first time.

## Running it against the cloud instead of locally

The script is written for the local stack. Against the AWS deployment, six things differ:

| Local | Cloud |
| --- | --- |
| `docker compose --profile app up -d` | `cd deploy/aws && AWS_REGION=ap-southeast-2 ./free.sh start` |
| http://localhost:8088 | The address `./free.sh status` prints. **It changes on every start** |
| Odoo at http://localhost:8069 | `./free.sh forward 8069` first, then http://localhost:8069 |
| Sign-ins from `.env` | `./free.sh credentials` |
| `Invoke-RestMethod` to localhost:8090 | The simulator is not exposed. `./free.sh forward 8090`, or set scenarios over SSM |
| Instance already warm | After a start, nginx answers within a minute but **`/api` returns 502 for about another 40 seconds** while the backend boots. Do not start the demo in that window |

Three traps worth knowing before you stand up in front of anyone:

- **A restart wipes the simulator's memory.** Scenarios you set earlier are gone, and payments made
  before the restart show as `MISSING_EXTERNAL_RECORD` in reconciliation. Set the scenarios after
  the instance is up, and not before.
- **Reconciliation only looks at invoices dated inside its period.** A run for "today" will not show
  a duplicate pair dated earlier in the week, however loudly you promised it.
- **`DUPLICATE` masks `MISMATCH`.** If the invoice you short-pay is one of the duplicate pair, rule
  1 wins and the mismatch never appears. Short-pay a clean invoice.

## Before the audience arrives

1. Start everything and wait for six healthy containers:

   ```
   docker compose --profile app up -d
   ```

   ```
   docker compose ps
   ```

2. In Odoo (http://localhost:8069, login from `.env`), post **five new vendor bills** under
   Invoicing > Vendors > Bills. The dev database has no open invoice left, so without this there is
   nothing to pay. Make two of them for the same vendor with the same Bill Reference (for example
   `DEMO-DUP-1`) and different bill dates. Note the five bill numbers; below they are A, B, C, D
   and E, where D and E are the pair.
3. Open three browser tabs: the dashboard (http://localhost:8088), Odoo, and a PowerShell window in
   the `finbridge` folder with

   ```powershell
   $h = @{ "X-Sim-Api-Key" = "<SIM_API_KEY from .env>" }
   ```

4. Set the scenarios (replace B and C with the bill numbers):

   ```powershell
   Invoke-RestMethod -Method Put -Uri "http://localhost:8090/sim/scenarios/<B>" -Headers $h -ContentType "application/json" -Body '{"scenario":"SLOW_SUCCESS"}'
   ```

   ```powershell
   Invoke-RestMethod -Method Put -Uri "http://localhost:8090/sim/scenarios/<C>" -Headers $h -ContentType "application/json" -Body '{"scenario":"SHORT_PAY"}'
   ```

5. Do not restart the simulator after this point: it keeps scenarios and payments in memory.

## 1. What it is (1 minute)

Show the component diagram in `architecture.md`. One sentence each: Odoo holds the bills; FinBridge
reads them, pays them through a SOAP payment service, and checks its own books against that
service's statement. Three integration styles: JSON-RPC to Odoo, SOAP to the payment service, REST
to the browser.

## 2. Synchronize from Odoo (2 minutes)

Sign in as the **integration operator** (`DEMO_OPERATOR_...` in `.env`).

| Do | Say |
| --- | --- |
| Integrations: press Run health check | Odoo, SOAP and REST each answer, with a response time |
| Invoices: press Sync from Odoo, confirm | The run is asynchronous; the page polls it |
| Show the run: 5 created | The five bills just posted. Drafts are never read |
| Press Sync again | 0 created. A bill is identified by its Odoo id, so a second run cannot duplicate it |
| Open an invoice: no Pay button | This role may not pay. The backend enforces it, not the page |

## 3. Pay an invoice (2 minutes)

Sign out; sign in as the **finance manager**.

| Do | Say |
| --- | --- |
| Open invoice A, press Pay, confirm | One `Idempotency-Key` was created when the dialog opened |
| Status `SUCCESS`, invoice `PAID` | The transaction row existed before anything was sent |
| Open the payment: no Retry, no SOAP viewer | Paying and retrying are held by different roles on purpose |

## 4. The lost answer (3 minutes)

Still the finance manager.

| Do | Say |
| --- | --- |
| Open invoice B, press Pay, confirm. Wait 10 seconds | The simulator pays and then answers after 15 seconds; FinBridge waits 10 |
| Status `TIMEOUT_UNKNOWN`, invoice in progress | Not `FAILED`. FinBridge does not know, and says so. Paying again here would be the classic double payment |
| Stay on invoice B | A second payment for it is refused by the backend (`409`) for as long as this one is unresolved |

Sign in as the **integration operator**.

| Do | Say |
| --- | --- |
| Payments: open B's payment, press Check status | It asks the service by transaction reference. It does not send the payment again |
| Status `SUCCESS`, still attempt 1 | One payment at the provider |
| Open the SOAP messages of both calls | The submit that timed out, and the status inquiry with its answer, as XML |
| In PowerShell: `Invoke-RestMethod -Uri "http://localhost:8090/sim/payments?invoiceReference=<B>" -Headers $h` | The provider's side: exactly one payment |

## 5. Reconcile (3 minutes)

Sign in as the **finance manager**. Pay invoice C (it reports `SUCCESS`) and invoice D. Then create a
settlement FinBridge never asked for:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8090/sim/orphans" -Headers $h -ContentType "application/json" -Body '{"invoiceReference":"UNKNOWN/001","supplierReference":"SUP-7","amount":48500,"currency":"INR"}'
```

| Do | Say |
| --- | --- |
| Reconciliation: Run reconciliation for today | It fetches the provider's statement over SOAP and compares |
| Counts by status | `MATCHED` for A and B. `MISMATCH` for C. `DUPLICATE` for D and E. `MISSING_INTERNAL_RECORD` for the orphan |
| Open the mismatch | Both sides field by field: requested the full amount, settled 90 percent |
| Open a duplicate | Same vendor, same reference, and one of them already paid |
| Review the mismatch: Needs investigation, with a note | A review is a record of a decision. It changes no invoice and no payment |

Older payments in the same period appear as `MISSING_EXTERNAL_RECORD` if the simulator was
restarted since they were made. Say so if asked: it is the simulator forgetting, and the rule
reporting exactly that.

## 6. Monitoring and administration (1 minute)

Sign in as the **administrator**.

| Do | Say |
| --- | --- |
| Integration logs, filter Failures | Every outbound call: duration, outcome, error code, masked payloads, correlation id |
| Settings | Which secrets are set, never their values |
| Users | Disabling a user or changing roles takes effect on their next request |

## If asked

| Question | Answer |
| --- | --- |
| Is it deployed to AWS? | No. The templates and scripts exist and are linted; they have never been run |
| What proves no double payment? | `PaymentHardeningIT`: eight threads pay one invoice at once; one payment results. The test fails without the fix |
| What happens if Odoo is down? | Three attempts, then the run is `FAILED` and the cursor does not move |
| Why a simulator? | A real provider's sandbox was out of scope. The contract is a real WSDL and both sides validate against it |
| What is missing? | `architecture.md`, Known limits; `security-review.md`, what was left |

## Afterwards

```
docker compose --profile app stop backend simulator frontend
```
