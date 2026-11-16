# FinBridge security review

Reviewed on 14 November 2026, at backend 0.7.0, simulator 0.4.0 and frontend 0.6.0. The review read
the code against section 8 of the architecture document and turned each property into a test
where one was missing. It is a code review by the author of the code, not an independent
penetration test.

## Findings that were fixed

| # | Severity | Finding | Fix | Proved by |
| ---: | --- | --- | --- | --- |
| 1 | High | **An invoice could be paid more than once by simultaneous requests.** The check "does this invoice already have a payment?" ran after the invoice row lock, but at MySQL's default isolation (REPEATABLE READ) it still read the snapshot taken before the lock, so it did not see the payment another request had just committed. With the fix removed, 8 simultaneous requests with 8 different keys produced 8 payments. | `PaymentStore.createOrReplay` and `startRetry` run at READ COMMITTED, and the idempotency key is looked up again after the lock. | `PaymentHardeningIT.eightSimultaneousPaymentsOfOneInvoiceWithDifferentKeysPayItExactlyOnce` (fails without the fix), `...eightSimultaneousCopiesOfOneRequest...` |
| 2 | Medium | **Two operators retrying the same failed payment at the same moment could both send it.** The eligibility check and "mark as sent" were two transactions. | One transaction, `PaymentStore.startRetry`, that takes the invoice lock before it reads the payment. | `PaymentHardeningIT.simultaneousRetriesOfOneFailedPaymentSendItOnce` |
| 3 | Medium | **Resetting a password did not end existing sessions.** A stolen access token kept working until it expired (up to 60 minutes). | New column `users.credentials_changed_at` (migration V3). A token issued before the reset is refused. | `SecurityHardeningIT.aPasswordResetRevokesTheTokensIssuedBeforeIt`, `JwtAuthenticationFilterTest` |
| 4 | Low | **A database outage during the token check answered with a bare 500**, outside the JSON error envelope. | The token filter answers 503 `DATABASE_UNAVAILABLE` in the envelope and stops the request. | `JwtAuthenticationFilterTest.aDatabaseOutageWhileCheckingTheToken...` |
| 5 | Low | **Spring's client-side SOAP validator only logged an invalid response** and let it through (found in Phase 4). | `ContractValidatingInterceptor` stops in both directions. | `SoapPaymentAdapterTest.aResponseThatBreaksTheSchemaIsRefused`, `...aRequestThatBreaksTheSchemaNeverLeaves` |
| 6 | Low | **The actuator index page** (`/actuator`) listed the available endpoints to any signed-in user. | `management.endpoints.web.discovery.enabled: false`. | `SecurityHardeningIT.onlyTheHealthEndpointOfTheActuatorExists` |

## Properties checked and found in order

| Area | Property | Proved by |
| --- | --- | --- |
| Tokens | Unsigned (`alg: none`), wrongly signed, tampered and garbage tokens are refused | `SecurityHardeningIT.aTokenWithNoSignatureOrWithAnotherKeyOrAnotherAlgorithmIsRefused`, `JwtServiceTest` |
| Tokens | A token is accepted only in the `Authorization: Bearer` header, never in the URL or a cookie | `SecurityHardeningIT.aTokenIsNotAcceptedInTheUrlOrInACookie` |
| Tokens | Roles are read from the database on every request; a disabled user's token stops at once | `RbacIT.aRoleChangeTakesEffectOnTheNextRequestWithTheSameToken`, `AuthIT.aDisabledUsersExistingTokenStopsWorkingImmediately` |
| Login | Unknown email and wrong password give the same answer in about the same time; five failures lock the account for 15 minutes | `AuthIT`, `AuthServiceTest` |
| Passwords | BCrypt (strength 12), 12 to 72 characters, never returned or logged | `RbacIT.adminCreatesAUserWhoCanThenLogIn`, `SecurityHardeningIT.aUserCannotGrantThemselvesARoleOrReadAnotherUsersHash` |
| Authorization | Every restricted endpoint refuses the wrong role, at the URL rule and again at the method | `RbacIT`, `PaymentIT`, `ReconciliationIT`, `OdooSyncIT`, `DashboardAndSettingsIT`, `PaymentHardeningIT` |
| Exposure | Only `/actuator/health` exists and it shows no details; the API documentation is closed outside the `dev` profile; the backend publishes no SOAP endpoint | `SecurityHardeningIT` |
| Browser | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store` on every response | `SecurityHardeningIT.responsesCarryTheBrowserHardeningHeadersAndAreNotCached` |
| CORS | Only the configured frontend origin is allowed; there is no wildcard | `SecurityHardeningIT.onlyTheConfiguredFrontendOriginPassesCors` |
| Input | Searches are parameterized; sorting and enum filters are allow-lists; page size is capped at 100 | `SecurityHardeningIT.hostileInputIsTreatedAsDataAndErrorsRevealNothingInternal`, `PageQueryTest` |
| Errors | No stack trace, class name or SQL reaches a client | same test, `OdooJson2AdapterTest.aRefusedKeyIsNotRetriedAndTheTracebackNeverLeavesTheAdapter` |
| XML | DTDs and external entities are refused by the simulator and by the backend's SOAP client | `SimulatorIT.aDoctypeWithAnExternalEntity...`, `SoapClientXxeTest` |
| Secrets | No secret has a default in the configuration; the settings API shows only "configured" or "missing"; stored payloads are masked | `DashboardAndSettingsIT.settingsShowConnectionsAndOnlyWhetherEachSecretIsSet`, `PayloadMaskerTest` |
| Frontend | No `dangerouslySetInnerHTML`, `innerHTML` or `eval` anywhere in `frontend/src` (checked by search); XML and notes are rendered as text; the production build carries a Content-Security-Policy with `script-src 'self'` | manual check, `frontend/dist/index.html` |
| Simulator | Its REST controls need `X-Sim-Api-Key`, compared in constant time; the key has no default and must be 16 characters or more | `SimulatorIT.theRestControlsNeedTheApiKey` |

## Known weaknesses that were NOT fixed

These are accepted for version 1 or belong to a later phase. None is hidden.

| # | Weakness | Why it stays | What would fix it |
| ---: | --- | --- | --- |
| 1 | Spring Boot 3.5.x no longer receives open-source security patches (since 30 June 2026). | Design decision 2 chose 3.5.x knowingly. | Move to Spring Boot 4.x, or buy extended support. |
| 2 | No dependency vulnerability scan is part of the build. `npm audit` was run by hand on 14 November 2026: it reported 10 advisories (2 critical, in development tools only), and after upgrading axios, react-router-dom, Vite, Vitest and PostCSS two moderate ones remain in react-router 6 (an open redirect through a crafted link target; a server-side-rendering issue that does not apply). Maven dependencies were not scanned. | The remaining two need react-router 7, a major upgrade. FinBridge only navigates to its own fixed routes. No Maven scanner was set up. | Upgrade to react-router 7; OWASP dependency-check or a hosted scanner in CI. |
| 3 | Locking an account after five failures lets anyone who knows an email lock that person out for 15 minutes. There is no limit per IP address. | A per-IP limit needs shared state or a gateway. | Rate limiting at the load balancer or a WAF in Phase 8. |
| 4 | The access token is in `sessionStorage`, readable by script on the page. | Stated in the design; the mitigations are the CSP and never rendering HTML from data. | An httpOnly cookie plus CSRF protection. |
| 5 | The Content-Security-Policy is a meta tag, so it cannot carry `frame-ancestors`, and there is no HSTS header. | Both are response headers of the web server. | nginx configuration in Phase 8; HSTS only once there is HTTPS. |
| 6 | Traffic is plain HTTP locally, and stays so in the cloud demo unless a domain is attached (design decision 8). | No domain yet. | ACM certificate on the load balancer. |
| 7 | The simulator's SOAP endpoint has no authentication. | It plays an external provider and is reachable only from the backend's network. | Mutual TLS or WS-Security against a real provider. |
| 8 | Odoo's API key lasts at most three months and is rotated by hand; an expired key shows as `ODOO_AUTH`. | An Odoo rule. | A calendar reminder; the key's expiry date is in the README. |
| 9 | There is no audit trail of administrative actions (who changed whose roles, who disabled whom) beyond the application log. Payments, sync runs, reconciliation runs, reviews and settings do record their user. | Not in the version 1 design. | An `audit_log` table written by the user service. |
| 10 | The "only one synchronization at a time" rule and the reconciliation worker are per application instance. | The design runs one backend instance. | A database lock (for example ShedLock) before scaling out. |

## How to repeat the review

```
cd backend
mvn clean verify
```

`SecurityHardeningIT`, `PaymentHardeningIT`, `JwtAuthenticationFilterTest` and `SoapClientXxeTest`
are the tests written for this review. To see finding 1 for yourself, change
`@Transactional(isolation = Isolation.READ_COMMITTED)` on `PaymentStore.createOrReplay` back to
`@Transactional` and run `PaymentHardeningIT`: it fails with "Expected size: 1 but was: 8".
