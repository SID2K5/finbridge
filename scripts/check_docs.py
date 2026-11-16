"""Checks the documentation set against the running system.

    docker compose --profile app up -d          # backend on 8081, simulator on 8090
    python scripts/check_docs.py [backend url] [simulator url]

What it checks, and what it does not:
  1. The two copies of the payments XSD are identical.
  2. The live WSDL has the operations, binding and service that docs/soap.md names, and
     docs/reference/payments.wsdl is a copy of it.
  3. Every request in the SoapUI project, sent to the simulator, gives the outcome its
     description promises. (SoapUI itself is not started.)
  4. Every request in the Postman collection, sent in order, gives the HTTP status its test
     expects, with variables passed along as the collection's scripts do. (The JavaScript
     tests themselves are NOT executed here; run the collection in Postman or newman for that.)
  5. The role column of docs/api.md (scripts/api_reference.py check).
  6. docs/api.md lists exactly the operations of the live OpenAPI document.
  7. docs/database.md describes exactly the tables of the live schema.
  8. Every relative link in README.md and docs/*.md points at a file that exists.

It pays one invoice if an open one exists, starts one synchronization and one reconciliation
run, and leaves a few "SOAPUI/..." payments in the simulator's memory.
"""
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.dom.minidom

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8081"
SIMULATOR = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8090"

results = []


def record(section, label, passed, detail=""):
    results.append((section, label, passed, detail))
    print("%-4s [%s] %s%s" % ("ok" if passed else "FAIL", section, label, (" - " + detail) if detail and not passed else ""))


def read(path):
    return open(os.path.join(ROOT, path), encoding="utf-8").read()


def env():
    values = {}
    for line in read(".env").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            values[k] = v
    return values


def http(method, url, body=None, headers=None, timeout=90):
    data = body.encode("utf-8") if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), dict(e.headers)


ENV = env()

# ---------------------------------------------------------------------------- 1. XSD copies
a = read("payment-simulator/src/main/resources/xsd/payments.xsd")
b = read("backend/src/main/resources/wsdl/payments.xsd")
record("contract", "the simulator's and the backend's payments.xsd are identical", a == b)

# ---------------------------------------------------------------------------- 2. WSDL
status, wsdl, _ = http("GET", SIMULATOR + "/ws/payments.wsdl")
record("contract", "the live WSDL is served", status == 200)
for name in ("PaymentsPort", "PaymentsPortSoap11", "PaymentsPortService", "SubmitPayment", "GetPaymentStatus",
             "GetSettlementStatement"):
    record("contract", "WSDL defines %s, as docs/soap.md says" % name,
           ('name="%s"' % name) in wsdl and name in read("docs/soap.md"))
normalise = lambda text: re.sub(r"http://[^/\"]+/ws", "http://HOST/ws", re.sub(r"\s+", " ", text)).strip()
record("contract", "docs/reference/payments.wsdl is a copy of the live WSDL",
       normalise(read("docs/reference/payments.wsdl")) == normalise(wsdl))

# ---------------------------------------------------------------------------- 3. SoapUI project
SIM_HEADERS = {"X-Sim-Api-Key": ENV.get("SIM_API_KEY", ""), "Content-Type": "application/json"}


def tag(text, name):
    found = re.search(r"<(?:\w+:)?%s(?:\s[^>]*)?>([^<]*)</" % name, text)
    return found.group(1) if found else None


# What each request must produce: (HTTP status, element, expected value).
SOAPUI_EXPECT = {
    ("SubmitPayment", "1"): (200, "PaymentStatus", "SUCCESS"),
    ("SubmitPayment", "2"): (200, "PaymentStatus", "SUCCESS"),
    ("SubmitPayment", "3"): (500, "ErrorCode", "IDEMPOTENCY_CONFLICT"),
    ("SubmitPayment", "4"): (500, "ErrorCode", "INVALID_REQUEST"),
    ("SubmitPayment", "5"): (500, "ErrorCode", "INVALID_REQUEST"),
    ("SubmitPayment", "6"): (200, "PaymentStatus", "FAILED"),
    ("GetPaymentStatus", "1"): (200, "PaymentStatus", "SUCCESS"),
    ("GetPaymentStatus", "2"): (500, "ErrorCode", "UNKNOWN_TRANSACTION"),
    ("GetSettlementStatement", "1"): (200, "TransactionReference", None),
    ("GetSettlementStatement", "2"): (200, "Entry", "ABSENT"),
}
project = xml.dom.minidom.parse(os.path.join(ROOT, "soapui", "FinBridge-payments-soapui-project.xml"))
http("PUT", SIMULATOR + "/sim/scenarios/SOAPUI/BILL/FAILED", '{"scenario":"FAILED"}', SIM_HEADERS)
first_external_id = None
seen = 0
for operation in project.getElementsByTagName("con:operation"):
    op_name = operation.getAttribute("name")
    for call in operation.getElementsByTagName("con:call"):
        seen += 1
        name = call.getAttribute("name")
        body = call.getElementsByTagName("con:request")[0].firstChild.data
        expected = SOAPUI_EXPECT.get((op_name, name.split(" ")[0]))
        status, text, _ = http("POST", SIMULATOR + "/ws", body, {"Content-Type": "text/xml; charset=utf-8", "SOAPAction": '""'})
        if expected is None:
            record("soapui", "%s / %s has an expectation in check_docs.py" % (op_name, name), False)
            continue
        code, element, value = expected
        if value == "ABSENT":
            good = status == code and tag(text, element) is None and "<" + element not in text
        elif value is None:
            good = status == code and tag(text, element) is not None
        else:
            good = status == code and tag(text, element) == value
        if (op_name, name.split(" ")[0]) == ("SubmitPayment", "1"):
            first_external_id = tag(text, "ExternalTransactionId")
        if (op_name, name.split(" ")[0]) == ("SubmitPayment", "2"):
            good = good and tag(text, "ExternalTransactionId") == first_external_id
        record("soapui", "%s / %s" % (op_name, name), good, "HTTP %s, %s=%s" % (status, element, tag(text, element)))
http("DELETE", SIMULATOR + "/sim/scenarios/SOAPUI/BILL/FAILED", None, SIM_HEADERS)
record("soapui", "the project holds exactly the %d requests this script knows" % len(SOAPUI_EXPECT), seen == len(SOAPUI_EXPECT))

# ---------------------------------------------------------------------------- 4. Postman collection
collection = json.loads(read("postman/FinBridge.postman_collection.json"))
variables = {"baseUrl": BACKEND, "simUrl": SIMULATOR, "adminEmail": ENV["BOOTSTRAP_ADMIN_EMAIL"],
             "adminPassword": ENV["BOOTSTRAP_ADMIN_PASSWORD"], "simApiKey": ENV.get("SIM_API_KEY", "")}


def substitute(text):
    text = text.replace("{{$guid}}", str(uuid.uuid4()))
    return re.sub(r"\{\{(\w+)\}\}", lambda m: str(variables.get(m.group(1), "{{%s}}" % m.group(1))), text)


def resolve(expression, payload):
    """The few forms the collection's scripts use to pick a value out of a response."""
    expression = expression.strip()
    if "$guid" in expression:
        return str(uuid.uuid4())
    data = payload.get("data") if isinstance(payload, dict) else None
    expression = re.sub(r"^pm\.response\.json\(\)\.data", "d", expression)
    if not re.match(r"^(d|rows|run)\b", expression):
        return None
    value = data
    for part in re.findall(r"\.(\w+)|\[(\d+)\]", expression):
        try:
            value = value[part[0]] if part[0] else value[int(part[1])]
        except (KeyError, IndexError, TypeError):
            return None
    return value


for folder in collection["item"]:
    for item in folder["item"]:
        scripts = {event["listen"]: "\n".join(event["script"]["exec"]) for event in item.get("event", [])}
        pre, tests = scripts.get("prerequest", ""), scripts.get("test", "")
        label = "%s / %s" % (folder["name"], item["name"])
        guard = re.search(r"if \(!pm\.environment\.get\('(\w+)'\)\) \{ pm\.execution\.skipRequest", pre)
        if guard and not variables.get(guard.group(1)):
            record("postman", label + " (skipped: nothing to act on)", True)
            continue
        if "pm.environment.set('today'" in pre:
            variables["today"] = time.strftime("%Y-%m-%d", time.gmtime())
        request = item["request"]
        headers = {h["key"]: substitute(h["value"]) for h in request.get("header", [])}
        if "noauth" not in pre and variables.get("accessToken"):
            headers["Authorization"] = "Bearer " + variables["accessToken"]
        body = substitute(request["body"]["raw"]) if "body" in request else None
        expected = int(re.search(r"to\.have\.status\((\d+)\)", tests).group(1))
        url = substitute(request["url"])
        url = url.split("?")[0] + ("?" + urllib.parse.quote(url.split("?", 1)[1], safe="=&,") if "?" in url else "")
        for attempt in range(240):
            status, text, response_headers = http(request["method"], url, body, headers)
            try:
                payload = json.loads(text) if text else {}
            except ValueError:
                payload = {}
            data = payload.get("data") if isinstance(payload, dict) else None
            running = isinstance(data, dict) and (data.get("status") == "RUNNING"
                                                  or (isinstance(data.get("run"), dict) and data["run"].get("status") == "RUNNING"))
            if "setNextRequest" in tests and running:
                time.sleep(0.5)
                continue
            break
        record("postman", label, status == expected, "expected HTTP %s, got %s" % (expected, status))
        if "pm.environment.unset('invoiceId')" in tests and isinstance(data, list) and not data:
            variables.pop("invoiceId", None)
        if "pm.environment.unset('recordId')" in tests and isinstance(data, list) and not data:
            variables.pop("recordId", None)
        for key, expression in re.findall(r"pm\.environment\.set\('(\w+)', ([^;]+)\);", tests):
            value = resolve(expression, payload)
            if value is not None:
                variables[key] = value

# ---------------------------------------------------------------------------- 5. roles
completed = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "api_reference.py"), "check", BACKEND],
                           capture_output=True, text=True)
record("api", "role column of docs/api.md: " + (completed.stdout.strip().splitlines() or ["no output"])[0],
       completed.returncode == 0, completed.stdout.strip()[-400:])

# ---------------------------------------------------------------------------- 6. api.md against OpenAPI
status, raw, _ = http("GET", BACKEND + "/v3/api-docs")
if status == 200:
    live = {(m.upper(), p) for p, ops in json.loads(raw)["paths"].items() for m in ops
            if m in ("get", "post", "put", "patch", "delete")}
    documented = set(re.findall(r"^\| (GET|POST|PUT|PATCH|DELETE) \| `([^`]+)` \|", read("docs/api.md"), re.M))
    record("api", "docs/api.md lists exactly the %d operations of the live OpenAPI document" % len(live),
           live == documented, "only live: %s; only documented: %s" % (sorted(live - documented), sorted(documented - live)))
else:
    record("api", "the live OpenAPI document is readable (dev profile)", False, "HTTP %s" % status)

# ---------------------------------------------------------------------------- 7. database.md against the schema
query = subprocess.run(
    ["docker", "exec", "finbridge-mysql", "sh", "-c",
     'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE" -e "SELECT table_name FROM information_schema.tables '
     "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'\""],
    capture_output=True, text=True)
live_tables = set(query.stdout.split())
documented_tables = set(re.findall(r"^## ([a-z_]+)$", read("docs/database.md"), re.M))
record("database", "docs/database.md describes exactly the %d tables of the live schema" % len(live_tables),
       bool(live_tables) and live_tables == documented_tables,
       "only live: %s; only documented: %s" % (sorted(live_tables - documented_tables), sorted(documented_tables - live_tables)))

# ---------------------------------------------------------------------------- 8. links
for path in ["README.md"] + sorted(glob.glob(os.path.join(ROOT, "docs", "*.md"))):
    full = path if os.path.isabs(path) else os.path.join(ROOT, path)
    text = open(full, encoding="utf-8").read()
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    broken = []
    for target in re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", text):
        if re.match(r"^[a-z]+://", target) or target.startswith("mailto:"):
            continue
        if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(full), target))):
            broken.append(target)
    for target in re.findall(r"`((?:docs|scripts|postman|soapui|deploy|backend|frontend|payment-simulator)/[A-Za-z0-9_./-]+\.[a-z]+)`", text):
        if "*" not in target and not os.path.exists(os.path.join(ROOT, target)):
            broken.append(target)
    record("links", "%s: every file it points at exists" % os.path.relpath(full, ROOT).replace("\\", "/"),
           not broken, ", ".join(sorted(set(broken))))

failed = [r for r in results if not r[2]]
print("\n%d checks, %d passed, %d failed" % (len(results), len(results) - len(failed), len(failed)))
sys.exit(1 if failed else 0)
