# Odoo integration

FinBridge reads suppliers and posted vendor bills from Odoo. It never writes to Odoo.

## What it talks to

| Item | Value |
| --- | --- |
| Odoo | 19.0 Community, the official `odoo:19.0` image, with PostgreSQL 16 as its private store |
| API | External JSON-2 API: `POST /json/2/<model>/<method>` |
| Authentication | `Authorization: bearer <API key>`; `X-Odoo-Database: <database>` |
| Request | A JSON object of named arguments (`domain`, `fields`, `offset`, `limit`, `order`, `context`) |
| Success | HTTP 200 with the method's return value as bare JSON |
| Error | HTTP 4xx or 5xx with `{name, message, arguments, context, debug}` |

JSON-2 is RPC over HTTP, not resource-style REST. XML-RPC and JSON-RPC are deprecated since Odoo
19 and are not used. Odoo Online offers the external API only on Custom plans, which is why this
project runs Odoo Community itself. The field names below were checked against a live Odoo 19.0
on 13 November 2026; another version should be checked at `/doc` on that instance first.

## What is read

| FinBridge | Odoo model | Filter | Fields |
| --- | --- | --- | --- |
| Suppliers | `res.partner` | `supplier_rank > 0`, archived ones included | `id`, `name`, `display_name`, `vat`, `email`, `phone`, `country_code`, `active`, `write_date` |
| Invoices | `account.move` | `move_type = in_invoice` and `state = posted` | `id`, `name`, `ref`, `partner_id`, `invoice_date`, `invoice_date_due`, `amount_untaxed`, `amount_tax`, `amount_total`, `currency_id`, `payment_state`, `invoice_origin`, `write_date` |

Draft and cancelled bills are never read. Odoo raises a partner's supplier rank when a bill is
posted for it, so a bill's partner is normally in the supplier list; if it is not, FinBridge
fetches that partner by id.

| Odoo | FinBridge column |
| --- | --- |
| `account.move.id` | `invoices.odoo_move_id` (unique) |
| `name` | `invoice_number` |
| `ref` | `vendor_reference` |
| `invoice_origin` | `po_reference` |
| `partner_id` | `supplier_id`, through `suppliers.odoo_partner_id` |
| `amount_untaxed`, `amount_tax`, `amount_total` | `untaxed_amount`, `tax_amount`, `total_amount` (read as exact decimals) |
| `currency_id` (its display name) | `currency` |
| `payment_state` | `odoo_payment_state` (informational) |
| `write_date` | `odoo_write_date` |

Odoo conventions the adapter handles: an empty field is JSON `false`, a many-to-one is
`[id, "name"]`, and datetimes are `yyyy-MM-dd HH:mm:ss` in UTC.

## A synchronization run

1. Refuse to start if a run is `RUNNING` (409) or the API key is missing (422).
2. Read the cursor: the `cursor_to` of the last run that moved it. No cursor means read everything.
   `{"fullResync": true}` ignores the cursor.
3. Page through suppliers with `write_date >= cursor`, ordered by write date and id. Each page is
   saved in its own database transaction.
4. Page through posted bills the same way. For each bill: validate, then create, update or mark
   unchanged by comparing write dates. One `synchronization_history` row per bill.
5. Close the run and move the cursor to the last write date fully processed.

| Run status | Means | Cursor |
| --- | --- | --- |
| `COMPLETED` | Everything was read and every bill accepted | moves |
| `PARTIAL` | Some bills were rejected, or a page failed after earlier pages were saved | moves as far as was fully processed |
| `FAILED` | Nothing usable: Odoo unreachable, key refused, first call failed | does not move |

The cursor comparison is inclusive because the API returns `write_date` to the second. The record
that set the cursor may therefore be read again and reported as unchanged; nothing sharing that
second is skipped.

### Rules for a bill

| Case | Result |
| --- | --- |
| New Odoo id | Created, `SYNCED` |
| Known id, same write date | `UNCHANGED`, nothing touched |
| Known id, later write date, invoice open or its payment failed | Updated, `UPDATED` |
| Known id, later write date, payment in progress or paid, amount or currency changed | **Not overwritten.** Marked `STALE` for a person to review |
| No number, partner, date or currency; currency not three letters; total missing or negative | Rejected with the reason. Never stored |

### When Odoo fails

| Condition | Behaviour |
| --- | --- |
| Timeout (5 s connect, 30 s read), connection refused, HTTP 5xx | Up to 3 attempts with 1 s then 2 s between them, then the run stops. Code `ODOO_TIMEOUT`, `ODOO_UNREACHABLE` or `ODOO_SERVER_ERROR` |
| HTTP 401 or 403 | No retry. `ODOO_AUTH`: the key is wrong, expired or lacks rights |
| Other 4xx | No retry. `ODOO_REJECTED` with Odoo's error name and message |
| 200 with something that is not the expected JSON | `ODOO_BAD_RESPONSE` |

Odoo's `debug` traceback is dropped inside the adapter. It is never stored, logged or returned.
All calls are reads, so a retry cannot duplicate anything.

## Local setup

`docker compose up -d odoo` starts Odoo at http://localhost:8069. The local instance was set up on
13 November 2026 and lives in the `finbridge-odoo-db-data` volume:

| Item | State |
| --- | --- |
| Database | `finbridge`: Invoicing and Purchase installed, company in India, currency INR, no demo data |
| Your login | `ODOO_ADMIN_LOGIN` and `ODOO_ADMIN_PASSWORD` in `.env` |
| Integration user | `finbridge-integration`: Invoicing and Purchase user rights, no password, so no web login |
| API key | Stored in `.env` as `ODOO_API_KEY`. **Expires on 6 February 2027** |
| Database manager | Switched off (`--no-database-list`), so there is no master password |
| Test data | Vendors and bills labelled `[TEST]`, created for the test runs |

Create your own vendors (Contacts) and vendor bills (Invoicing > Vendors > Bills) by hand and
confirm them. Only posted bills synchronize.

### Renewing the API key

Odoo caps an ordinary user's key at **90 days** and refuses anything longer with "You cannot
exceed 90.0 days", so this comes round every quarter. An expired key shows as `ODOO_AUTH` in the
health check.

**An administrator cannot create a key for another user.** In Odoo 19 a user's form has a Security
tab with Change Password and two-factor authentication only; API keys live under the signed-in
user's own preferences. So either:

- **Sign in as the integration user** (give it a password first, from its form), then avatar >
  My Profile > Account Security > New API Key, and clear the password afterwards; or
- **Generate it in the Odoo shell** as that user:

  ```python
  user = env["res.users"].search([("login", "=", "finbridge-integration@finbridge.local")])
  expires = datetime.datetime.now() + datetime.timedelta(days=89)
  key = env["res.users.apikeys"].with_user(user)._generate("rpc", "finbridge backend", expires)
  ```

  `with_user(user)` is the part that matters. Without it the key is created for whoever the shell
  is running as — in a plain `odoo shell` that is `__system__`, the superuser, which would give the
  integration unrestricted access to the database. Check afterwards:
  `select k.id, u.login from res_users_apikeys k join res_users u on u.id = k.user_id`.

Then put the key into `.env` as `ODOO_API_KEY` and restart the backend.

### Starting from nothing

After `docker compose down -v` the Odoo database is gone. Recreate it with:

```
docker compose up -d odoo-db
docker compose run --rm odoo odoo -d finbridge -i account,purchase --stop-after-init --without-demo=True
docker compose up -d odoo
```

Then create the integration user and its key in Odoo's web page as above. (This procedure is
written from what was done on 13 November 2026 with a script; it has not been repeated by hand.)

## The mock adapter

`mvn spring-boot:run "-Dspring-boot.run.profiles=dev,mock-odoo"` replaces Odoo with an in-memory
stand-in: three suppliers, three valid bills and one invalid bill. It exists for the automated
tests and for working without Odoo. It cannot pass for the real thing: the log prints a banner,
`GET /api/integrations/status` reports `mode: MOCK`, every record is labelled `[MOCK]`, every log
entry's operation starts with `MOCK `, and the dashboard shows a banner on every page.

## Proving the adapter against a real Odoo

```powershell
$env:ODOO_LIVE_URL="http://localhost:8069"; $env:ODOO_LIVE_DB="finbridge"; $env:ODOO_LIVE_API_KEY="<key>"
cd backend
mvn verify "-Dit.test=OdooLiveIT" "-Dtest=NoSuchUnit" "-Dsurefire.failIfNoSpecifiedTests=false"
```

It only reads: a ping, a wrong key that must give `ODOO_AUTH`, and the suppliers and bills it finds.

## Limits

- A bill deleted or reset to draft in Odoo after it was synchronized stays in FinBridge.
- A bill changed twice within one second, with a sync between the two changes, keeps the first
  version until it changes again.
- Purchase orders are not stored; only the bill's origin text is kept as a reference.
- Odoo will not post a bill without a vendor, a date or a currency, so the rejection rule cannot be
  shown with a real Odoo. It is proven by `OdooSyncIT` and visible with the mock adapter.
