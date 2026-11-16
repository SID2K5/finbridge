"""Generates docs/api.md from the backend's own OpenAPI document, and can check it live.

    python scripts/api_reference.py generate [backend url]   # default http://localhost:8081
    python scripts/api_reference.py check    [backend url]

"generate" fetches /v3/api-docs (open under the dev profile), saves it as
docs/reference/openapi.json and writes docs/api.md. Paths, methods and parameters come from
the running application; only the role column and a few one-line descriptions are kept here.

"check" proves the role column: it creates one temporary user per role, calls every endpoint
as each role and as nobody, and compares "refused" (401/403) with the table below. It needs
BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD from .env. The temporary users are
disabled again at the end.
"""
import json
import os
import secrets
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A, F, O = "ADMIN", "FINANCE_MANAGER", "INTEGRATION_OPERATOR"
ALL, FINANCE, OPS, ADMIN, PUBLIC = (A, F, O), (A, F), (A, O), (A,), ()

# Who may call what. This is documentation; "check" verifies it against the running backend.
ROLES = {
    ("POST", "/api/auth/login"): PUBLIC,
    ("GET", "/api/auth/me"): ALL,
    ("POST", "/api/auth/register"): ADMIN,
    ("GET", "/api/users"): ADMIN,
    ("GET", "/api/users/{id}"): ADMIN,
    ("PATCH", "/api/users/{id}"): ADMIN,
    ("PUT", "/api/users/{id}/roles"): ADMIN,
    ("POST", "/api/users/{id}/reset-password"): ADMIN,
    ("GET", "/api/roles"): ADMIN,
    ("GET", "/api/dashboard/summary"): ALL,
    ("GET", "/api/dashboard/transactions"): ALL,
    ("GET", "/api/dashboard/invoice-trend"): ALL,
    ("GET", "/api/dashboard/recent-activity"): ALL,
    ("GET", "/api/dashboard/reconciliation-summary"): FINANCE,
    ("GET", "/api/suppliers"): ALL,
    ("GET", "/api/suppliers/{id}"): ALL,
    ("GET", "/api/invoices"): ALL,
    ("GET", "/api/invoices/{id}"): ALL,
    ("GET", "/api/invoices/{id}/history"): ALL,
    ("POST", "/api/invoices/sync"): OPS,
    ("POST", "/api/payments"): FINANCE,
    ("GET", "/api/payments"): ALL,
    ("GET", "/api/payments/{id}"): ALL,
    ("GET", "/api/payments/{id}/status"): ALL,
    ("POST", "/api/payments/{id}/status-check"): OPS,
    ("POST", "/api/payments/{id}/retry"): OPS,
    ("POST", "/api/reconciliation/run"): FINANCE,
    ("GET", "/api/reconciliation/runs"): FINANCE,
    ("GET", "/api/reconciliation/runs/{id}"): FINANCE,
    ("GET", "/api/reconciliation"): FINANCE,
    ("GET", "/api/reconciliation/{id}"): FINANCE,
    ("PATCH", "/api/reconciliation/{id}/review"): FINANCE,
    ("GET", "/api/integrations/status"): ALL,
    ("POST", "/api/integrations/health-check"): OPS,
    ("POST", "/api/integrations/odoo/sync"): OPS,
    ("GET", "/api/integrations/odoo/sync-runs"): OPS,
    ("GET", "/api/integrations/odoo/sync-runs/{id}"): OPS,
    ("GET", "/api/integrations/logs"): OPS,
    ("GET", "/api/integrations/logs/{id}"): OPS,
    ("POST", "/api/integrations/logs/{id}/retry"): OPS,
    ("GET", "/api/integrations/config"): ADMIN,
    ("PUT", "/api/integrations/config/{key}"): ADMIN,
}

# One line for the operations whose controller method carries no summary of its own.
DESCRIPTIONS = {
    ("GET", "/api/users"): "List users. Sort: email, fullName, createdAt, lastLoginAt",
    ("GET", "/api/users/{id}"): "One user",
    ("PATCH", "/api/users/{id}"): "Change the name, enable or disable. The last enabled administrator cannot be disabled",
    ("PUT", "/api/users/{id}/roles"): "Replace the user's roles. The last enabled administrator cannot lose ADMIN",
    ("POST", "/api/users/{id}/reset-password"): "Set a new password (12 to 72 characters). Ends the user's existing sessions. 204",
    ("GET", "/api/roles"): "The three role names",
    ("GET", "/api/dashboard/summary"): "Invoice count and value, open payable, paid amount, payments by status, open discrepancies, last sync",
    ("GET", "/api/dashboard/transactions"): "Payments per status, optionally for a period",
    ("GET", "/api/dashboard/invoice-trend"): "Invoices first synchronized per UTC day; days 1 to 365, default 30",
    ("GET", "/api/dashboard/recent-activity"): "The latest 20 integration log entries, summary fields only",
    ("GET", "/api/dashboard/reconciliation-summary"): "Counts per result and per review state for the latest run; no data if none has run",
    ("GET", "/api/suppliers"): "List suppliers; q searches name and tax id. Sort: name, odooPartnerId, updatedAt",
    ("GET", "/api/suppliers/{id}"): "One supplier",
    ("GET", "/api/invoices"): "List invoices. Sort: invoiceNumber, invoiceDate, dueDate, totalAmount, status, supplierName",
    ("GET", "/api/invoices/{id}"): "One invoice with its supplier and its payment transactions",
    ("GET", "/api/invoices/{id}/history"): "What each synchronization run did with this invoice",
    ("GET", "/api/payments"): "List payments; reference searches the FinBridge reference and the external id. Sort: requestedAt, status, requestedAmount, transactionReference",
    ("GET", "/api/reconciliation/runs"): "List reconciliation runs. Sort: id, startedAt, periodFrom, status",
    ("GET", "/api/integrations/odoo/sync-runs"): "List synchronization runs. Sort: id, startedAt, status",
    ("GET", "/api/integrations/odoo/sync-runs/{id}"): "One run with a page of its per-record history",
    ("GET", "/api/integrations/logs"): "List integration log entries, without payloads. Sort: createdAt, durationMs, operation, outcome",
    ("GET", "/api/integrations/logs/{id}"): "One log entry with its masked request and response payloads",
    ("GET", "/api/integrations/config"): "Runtime settings, connection addresses, and whether each secret is configured (never its value)",
}

TAG_ORDER = ["Authentication", "Users and roles", "Dashboard", "Suppliers", "Invoices", "Odoo synchronization",
             "Payments", "Reconciliation", "Integration monitoring", "Settings"]
SHORT = {A: "A", F: "F", O: "O"}


def http(method, url, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def operations(spec):
    for path, item in sorted(spec["paths"].items()):
        for method, op in item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                yield method.upper(), path, op


def generate(base):
    status, raw = http("GET", base + "/v3/api-docs")
    if status != 200:
        sys.exit("Could not read %s/v3/api-docs (HTTP %s). The API documentation is open only under the "
                 "dev profile." % (base, status))
    spec = json.loads(raw)
    os.makedirs(os.path.join(ROOT, "docs", "reference"), exist_ok=True)
    json.dump(spec, open(os.path.join(ROOT, "docs", "reference", "openapi.json"), "w", encoding="utf-8", newline="\n"),
              indent=2, sort_keys=True)

    by_tag = {}
    missing = []
    for method, path, op in operations(spec):
        if (method, path) not in ROLES:
            missing.append("%s %s" % (method, path))
        by_tag.setdefault((op.get("tags") or ["Other"])[0], []).append((method, path, op))
    stale = [("%s %s" % key) for key in ROLES if key not in {(m, p) for m, p, _ in operations(spec)}]
    if missing or stale:
        sys.exit("The role table in scripts/api_reference.py does not match the application.\n"
                 "  in the application but not in the table: %s\n  in the table but not in the application: %s"
                 % (missing or "none", stale or "none"))

    out = ["# FinBridge REST API", "",
           "Generated by `scripts/api_reference.py` from the running backend's OpenAPI document "
           "(application version %s, %d operations). The machine-readable document is "
           "`docs/reference/openapi.json`; with the `dev` profile it is also served at `/v3/api-docs`, "
           "with Swagger UI at `/swagger-ui.html`." % (spec["info"]["version"], len(ROLES)), "",
           "## Conventions", "",
           "- Base path `/api`. JSON only. Every endpoint except login needs `Authorization: Bearer <token>`.",
           "- Roles: **A** = ADMIN, **F** = FINANCE_MANAGER, **O** = INTEGRATION_OPERATOR. A wrong role gets "
           "`403 ACCESS_DENIED`; no or an invalid token gets `401 UNAUTHENTICATED`.",
           "- Success: `{ \"data\": ..., \"meta\": { \"page\", \"size\", \"totalElements\", \"totalPages\" }, "
           "\"correlationId\", \"timestamp\" }`. `meta` is present on lists. Fields that are null are left out.",
           "- Error: `{ \"error\": { \"code\", \"message\", \"fieldErrors\": [ { \"field\", \"message\" } ] }, "
           "\"correlationId\", \"timestamp\" }`.",
           "- Lists take `page` (from 0), `size` (1 to 100, default 20) and `sort=field,asc|desc`. Only the "
           "fields named for each list are accepted.",
           "- Dates are `yyyy-MM-dd`; instants are ISO 8601 in UTC (`2026-11-13T09:30:00Z`).",
           "- Every response carries `X-Correlation-Id`. Send your own UUID in that header and it is used.",
           "", "| Status | Meaning |", "| --- | --- |",
           "| 200, 201, 204 | Done |", "| 202 | Accepted; a run was started in the background. Poll it |",
           "| 400 | The request is malformed or a value is invalid (`fieldErrors` says which) |",
           "| 401 | No valid token, or the session was ended |", "| 403 | The role is not allowed |",
           "| 404 | No such record |", "| 409 | Conflicts with the current state (a duplicate, a run in progress, a concurrent change) |",
           "| 422 | Well formed, but a business rule forbids it |",
           "| 502, 504 | An external system failed or timed out, and nothing was changed |",
           "| 503 | The database is unavailable |", ""]

    tags = [t for t in TAG_ORDER if t in by_tag] + sorted(t for t in by_tag if t not in TAG_ORDER)
    for tag in tags:
        out += ["## " + tag, "", "| Method | Path | Roles | What it does | Query parameters |", "| --- | --- | --- | --- | --- |"]
        for method, path, op in sorted(by_tag[tag], key=lambda x: (x[1], x[0])):
            roles = ROLES[(method, path)]
            who = " ".join(SHORT[r] for r in roles) if roles else "Public"
            text = op.get("summary") or DESCRIPTIONS.get((method, path), "")
            params = [p["name"] for p in op.get("parameters", []) if p.get("in") == "query"]
            headers = [p["name"] for p in op.get("parameters", []) if p.get("in") == "header"]
            extra = ", ".join("`%s`" % p for p in params)
            if headers:
                extra = (extra + "; " if extra else "") + "header " + ", ".join("`%s`" % h for h in headers)
            out.append("| %s | `%s` | %s | %s | %s |" % (method, path, who, text.replace("|", "/"), extra or ""))
        out.append("")

    open(os.path.join(ROOT, "docs", "api.md"), "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print("wrote docs/api.md and docs/reference/openapi.json: %d operations in %d groups" % (len(ROLES), len(tags)))


def read_env():
    env = {}
    for line in open(os.path.join(ROOT, ".env"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def check(base):
    env = read_env()
    status, raw = http("POST", base + "/api/auth/login", {"email": env["BOOTSTRAP_ADMIN_EMAIL"],
                                                           "password": env["BOOTSTRAP_ADMIN_PASSWORD"]})
    if status != 200:
        sys.exit("Could not sign in as the bootstrap administrator (HTTP %s)" % status)
    admin = json.loads(raw)["data"]["accessToken"]

    tokens, created = {}, []
    for role in (A, F, O):
        email = "doc-check-%s-%s@finbridge.local" % (role.lower().replace("_", "-"), secrets.token_hex(4))
        password = secrets.token_urlsafe(16)
        status, raw = http("POST", base + "/api/auth/register",
                           {"email": email, "fullName": "Documentation check", "password": password, "roles": [role]}, admin)
        if status != 201:
            sys.exit("Could not create the temporary %s user (HTTP %s)" % (role, status))
        created.append(json.loads(raw)["data"]["id"])
        status, raw = http("POST", base + "/api/auth/login", {"email": email, "password": password})
        tokens[role] = json.loads(raw)["data"]["accessToken"]

    wrong = []
    calls = 0
    try:
        for (method, path), allowed in sorted(ROLES.items()):
            if not allowed:
                continue   # login is public; signing in above already proved it
            # An id that does not exist and a body that is invalid: an allowed caller gets 400, 404,
            # 409 or 422 and changes nothing; a caller without the role is stopped earlier with 403.
            url = base + path.replace("{id}", "999999999").replace("{key}", "no.such.setting")
            body = None if method == "GET" else {}
            if (method, path) in (("POST", "/api/integrations/odoo/sync"), ("POST", "/api/invoices/sync"),
                                  ("POST", "/api/integrations/health-check")):
                # These would really start something for an allowed role; check only who is refused.
                for role in (A, F, O):
                    if role not in allowed:
                        calls += 1
                        status, _ = http(method, url, body, tokens[role])
                        if status != 403:
                            wrong.append("%s %s as %s: expected 403, got %s" % (method, path, role, status))
            else:
                for role in (A, F, O):
                    calls += 1
                    status, _ = http(method, url, body, tokens[role])
                    refused = status == 403
                    if refused != (role not in allowed):
                        wrong.append("%s %s as %s: documented %s, got HTTP %s" % (
                            method, path, role, "allowed" if role in allowed else "refused", status))
            calls += 1
            status, _ = http(method, url, body)
            if status != 401:
                wrong.append("%s %s without a token: expected 401, got %s" % (method, path, status))
    finally:
        for user_id in created:
            http("PATCH", base + "/api/users/%s" % user_id, {"enabled": False}, admin)

    print("role check: %d calls against %d operations, %d mismatches" % (calls, len(ROLES) - 1, len(wrong)))
    for line in wrong:
        print("  MISMATCH " + line)
    return 0 if not wrong else 1


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "generate"
    backend = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8081"
    if command == "generate":
        generate(backend)
    elif command == "check":
        sys.exit(check(backend))
    else:
        sys.exit(__doc__)
