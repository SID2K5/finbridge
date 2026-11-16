# SOAP payment integration

FinBridge pays invoices through an external payment service that speaks SOAP. In this project
that service is the **payment simulator** (`payment-simulator/`), a separate application that
behaves like a provider and can be told to misbehave.

## The contract

| Item | Value |
| --- | --- |
| Protocol | SOAP 1.1 over HTTP, document/literal |
| Namespace | `http://finbridge.com/payments/v1` |
| Endpoint | `http://localhost:8090/ws` |
| WSDL | `http://localhost:8090/ws/payments.wsdl` (a copy is in `docs/reference/payments.wsdl`) |
| Port type, binding, service | `PaymentsPort`, `PaymentsPortSoap11`, `PaymentsPortService` |
| Schema | `payment-simulator/src/main/resources/xsd/payments.xsd` |
| Backend's copy of the schema | `backend/src/main/resources/wsdl/payments.xsd`, byte for byte the same |

It is contract-first. The XSD is written by hand; the simulator generates the WSDL from it at
runtime, and both sides generate their JAXB classes from it at build time (`jaxb2-maven-plugin`).
Both sides validate every request and every response against it. If you change the contract,
change both copies of the XSD together. `scripts/check_docs.py` fails if they differ.

## Operations

| Operation | Request | Response | Used for |
| --- | --- | --- | --- |
| SubmitPayment | `SubmitPaymentRequest` | `SubmitPaymentResponse` | Sending a payment instruction |
| GetPaymentStatus | `GetPaymentStatusRequest` | `GetPaymentStatusResponse` | Finding out what happened to a payment that is pending or whose answer was lost |
| GetSettlementStatement | `GetSettlementStatementRequest` | `GetSettlementStatementResponse` | The provider's own record of a period, for reconciliation |

| Element | Fields |
| --- | --- |
| `SubmitPaymentRequest` | `TransactionReference` (1-40), `InvoiceReference` (1-64), `SupplierReference` (1-64), `RequestedAmount` (decimal, at most 4 fraction digits, above 0), `Currency` (`[A-Z]{3}`), `RequestTimestamp` (dateTime), `IdempotencyKey` (1-64) |
| `SubmitPaymentResponse`, `GetPaymentStatusResponse` | `ExternalTransactionId`, `TransactionReference`, `InvoiceReference`, `ProcessedAmount`, `Currency`, `PaymentStatus` (`SUCCESS`, `PENDING`, `FAILED`, `REJECTED`), `ResponseTimestamp`, `FailureReason` (optional) |
| `GetPaymentStatusRequest` | `TransactionReference` |
| `GetSettlementStatementRequest` | `FromDate`, `ToDate` (dates, both inclusive, UTC) |
| `GetSettlementStatementResponse` | `Entry` (0 or more): the fields of a payment response plus `SupplierReference` |

FinBridge sends the invoice number as `InvoiceReference` and `SUP-<Odoo partner id>` as
`SupplierReference`.

### A submit request

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:pay="http://finbridge.com/payments/v1">
  <soapenv:Header/>
  <soapenv:Body>
    <pay:SubmitPaymentRequest>
      <pay:TransactionReference>FB-20261113-SOAPUI01</pay:TransactionReference>
      <pay:InvoiceReference>BILL/2026/11/0009</pay:InvoiceReference>
      <pay:SupplierReference>SUP-42</pay:SupplierReference>
      <pay:RequestedAmount>50000.0000</pay:RequestedAmount>
      <pay:Currency>INR</pay:Currency>
      <pay:RequestTimestamp>2026-11-13T09:30:00Z</pay:RequestTimestamp>
      <pay:IdempotencyKey>3f1c9a52-6f0e-4a57-9d2b-1c8e7a4b0d11</pay:IdempotencyKey>
    </pay:SubmitPaymentRequest>
  </soapenv:Body>
</soapenv:Envelope>
```

## Outcomes and faults

A business outcome is a normal response with HTTP 200, also when the payment `FAILED` or was
`REJECTED`. A **fault** (HTTP 500) means the request could not be processed at all. Its detail
carries a `PaymentFault` element with an `ErrorCode` that callers switch on:

| Fault code | `ErrorCode` | Meaning | What FinBridge does |
| --- | --- | --- | --- |
| Client | `INVALID_REQUEST` | The request breaks the schema | Marks the payment `FAILED` with the reason. (FinBridge validates before sending, so this should not happen) |
| Client | `IDEMPOTENCY_CONFLICT` | The key was used with a different payload, or the reference belongs to a payment that did not fail | Marks the payment `FAILED` and logs it as a defect |
| Client | `UNKNOWN_TRANSACTION` | A status inquiry for a reference the service never received | Sends the same attempt again with the same key: safe, nothing can be paid twice |
| Server | `SERVICE_UNAVAILABLE` | An outage; nothing was recorded | Marks the payment `FAILED`; an operator may retry |

## How the backend treats each kind of failure

The SOAP adapter turns everything that can go wrong into one of four kinds, because the payment
module needs to know exactly one thing: could the payment have happened?

| Kind | When | Could it have been paid? | Payment becomes |
| --- | --- | --- | --- |
| `UNREACHABLE` | The connection could not be opened | No, the request never left | `FAILED` |
| `FAULT` | The service answered with a fault | No | `FAILED` (or a resend, for `UNKNOWN_TRANSACTION`) |
| `TIMEOUT` | Sent; no answer within 10 seconds, or the connection dropped | Maybe | `TIMEOUT_UNKNOWN` |
| `BAD_RESPONSE` | Sent and answered, but the answer breaks the schema or cannot be parsed | Maybe | `TIMEOUT_UNKNOWN` |

`TIMEOUT_UNKNOWN` is never treated as a failure. The only way out is a status inquiry
(`POST /api/payments/{id}/status-check`, or "retry", which asks first).

Timeouts are 3 seconds to connect and 10 seconds to read (`finbridge.payment.*` in
`application.yml`). The adapter never retries by itself; whether to try again is a business
decision.

## Idempotency

- The browser creates one `Idempotency-Key` per payment intention and sends it with
  `POST /api/payments`. The same key and body again returns the same transaction and sends nothing.
- The first attempt goes to the payment service under that same key. A resend of that attempt
  (after `UNKNOWN_TRANSACTION`) uses the same key again.
- A retry after a business failure is a new attempt: same `TransactionReference`, a new key derived
  from the original and the attempt number. Without that, the service would replay the failure.
- The simulator accepts a known reference under a new key only when that payment `FAILED`.

## Simulator scenarios

Set a scenario for an invoice reference before paying it. The default is `SUCCESS`.

| Scenario | The simulator | FinBridge shows |
| --- | --- | --- |
| `SUCCESS` | pays in full | `SUCCESS`, invoice `PAID` |
| `PENDING_THEN_SUCCESS` | answers `PENDING`; settles on the second status inquiry | `PENDING`, then `SUCCESS` after two status checks |
| `FAILED` | fails with `PROCESSING_ERROR` | `FAILED`, invoice `PAYMENT_FAILED`; can be retried |
| `REJECTED` | rejects with `ACCOUNT_BLOCKED` | `REJECTED`; final |
| `SLOW_SUCCESS` | pays, then answers after 15 seconds | `TIMEOUT_UNKNOWN` after 10 seconds; a status check finds `SUCCESS` |
| `SHORT_PAY` | pays 90 percent | `SUCCESS` with a lower processed amount; reconciliation reports a mismatch |
| `OUTAGE` | `SERVICE_UNAVAILABLE` fault, records nothing | `FAILED`; can be retried |

### REST controls

All need the header `X-Sim-Api-Key` (the value of `SIM_API_KEY` in `.env`). Invoice references
contain slashes, so the reference is simply the rest of the path.

| Method | Path | Does |
| --- | --- | --- |
| GET | `/sim/health` | Status, start time, number of payments and scenarios |
| GET | `/sim/scenarios` | The scenarios that are set |
| PUT | `/sim/scenarios/<invoice reference>` | Body `{"scenario": "FAILED"}` |
| DELETE | `/sim/scenarios/<invoice reference>` | Back to the default |
| POST | `/sim/orphans` | Body `{"invoiceReference", "supplierReference", "amount", "currency"}`: a settlement FinBridge never asked for |
| GET | `/sim/payments?invoiceReference=` | What the simulator recorded |

PowerShell:

```powershell
$h = @{ "X-Sim-Api-Key" = "<SIM_API_KEY from .env>" }
Invoke-RestMethod -Method Put -Uri "http://localhost:8090/sim/scenarios/BILL/2026/11/0009" -Headers $h -ContentType "application/json" -Body '{"scenario":"SLOW_SUCCESS"}'
Invoke-RestMethod -Method Delete -Uri "http://localhost:8090/sim/scenarios/BILL/2026/11/0009" -Headers $h
```

## Trying it by hand

- **SoapUI:** open `soapui/FinBridge-payments-soapui-project.xml` with the simulator running. It
  holds one request per case in this document.
- **Postman:** the collection in `postman/` drives the backend's REST API, which in turn makes these
  SOAP calls. Each call's request and response XML is then readable under Integration logs in the
  dashboard, or with `GET /api/integrations/logs/{id}`.

## XML safety

Both sides refuse DTDs and external entities. `SimulatorIT.aDoctypeWithAnExternalEntityIsRefused…`
sends an attack to the simulator; `SoapClientXxeTest` makes a real HTTP server send one to the
backend's client. Stored SOAP payloads are masked for credentials and cut off at 16,000 characters.

## Limits

- The simulator keeps payments in memory. After a restart it knows none of them: an older pending
  payment is resent on its next status check (safe), and settled ones appear as
  `MISSING_EXTERNAL_RECORD` in reconciliation.
- Its SOAP endpoint has no authentication. A real provider would require mutual TLS or WS-Security.
- There is one currency per payment and no conversion.
