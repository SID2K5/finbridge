# FinBridge test report

Generated on 13 November 2026, 23:20 UTC by `scripts/test_report.py` from the result files of the last run of each suite. No number here is typed by hand.

## Totals

| Suite | Tests | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Backend unit tests (`mvn test`) | 82 | 82 | 0 | 0 |
| Backend integration tests on MySQL 8.4 in Docker (`mvn verify`) | 97 | 94 | 0 | 3 |
| Payment simulator over real HTTP (`mvn verify`) | 17 | 17 | 0 | 0 |
| Frontend unit tests (`npm test`) | 18 | 18 | 0 | 0 |
| **All suites** | **214** | **211** | **0** | **3** |

**Failures:** none.

**Skipped:** `OdooLiveIT.aWrongKeyIsReportedAsAnAuthenticationError`, `OdooLiveIT.pingSucceedsWithTheRealKey`, `OdooLiveIT.suppliersAndVendorBillsCanBeReadWithTheFieldsTheMapperExpects`. The `OdooLiveIT` tests need a running Odoo and its API key (`ODOO_LIVE_API_KEY`); they are skipped in a normal build and were last run by hand on 13 November 2026 against Odoo 19.0, where all three passed.

## The twelve required cases

These twelve cases were derived from the rules in the Phase 1 design document, because the original list from the project brief was not available when this report was written. If the brief's list differs, change `REQUIRED_CASES` in `scripts/test_report.py` and run it again.

| # | Case | Result | Proved by |
| ---: | --- | --- | --- |
| 1 | Login succeeds with valid credentials and is refused with invalid ones, without revealing which emails exist | PASSED | `AuthIT.loginReturnsATokenThatOpensAProtectedEndpoint`<br>`AuthIT.wrongPasswordAndUnknownEmailGetTheSame401`<br>`AuthIT.fiveWrongPasswordsLockTheAccountInTheDatabase` |
| 2 | Role-based access is enforced by the backend for every role | PASSED | `RbacIT.financeManagerAndOperatorCannotReachUserManagement`<br>`PaymentIT.creatingAndRetryingAreHeldByDifferentRoles`<br>`ReconciliationIT.integrationOperatorsHaveNoAccessToReconciliation`<br>`OdooSyncIT.onlyAdminsAndOperatorsMaySynchronizeOrReadLogs` |
| 3 | Odoo synchronization creates suppliers and invoices from vendor bills | PASSED | `OdooSyncIT.firstRunCreatesSuppliersAndInvoicesAcrossPages`<br>`OdooJson2AdapterTest.vendorBillsAreRequestedTheJson2WayAndParsedExactly` |
| 4 | Synchronizing again never duplicates an invoice | PASSED | `OdooSyncIT.runningAgainCreatesNoDuplicatesAndReportsUnchanged`<br>`OdooSyncIT.anIncrementalRunReadsOnlyWhatChangedSinceTheCursor` |
| 5 | An invalid bill is rejected with a reason and the rest of the run is kept | PASSED | `OdooSyncIT.anInvalidBillIsRejectedWithAReasonAndTheRestIsKept`<br>`InvoiceSyncServiceTest.billsMissingRequiredDataAreRejectedWithAReason` |
| 6 | A payment is sent over SOAP and a success pays the invoice | PASSED | `PaymentIT.aSuccessfulPaymentPaysTheInvoice`<br>`SoapPaymentAdapterTest.submitSendsTheContractRequestAndReadsTheResult`<br>`SimulatorIT.aPaymentSucceedsByDefault` |
| 7 | A duplicate payment is impossible: same key replays, another key is refused, also under concurrency | PASSED | `PaymentIT.theSameKeyAndBodyReplaysTheTransactionAndSendsNothing`<br>`PaymentIT.anInvoiceThatIsPaidCannotBePaidAgainWithANewKey`<br>`PaymentHardeningIT.eightSimultaneousPaymentsOfOneInvoiceWithDifferentKeysPayItExactlyOnce`<br>`PaymentHardeningIT.eightSimultaneousCopiesOfOneRequestBecomeOneTransactionAndOneCallToThePaymentService`<br>`SimulatorIT.theSameKeyAndPayloadReturnsTheOriginalResultAndCreatesNothingNew` |
| 8 | A timeout is never recorded as a failure and is resolved by a status inquiry without paying twice | PASSED | `PaymentIT.aTimeoutIsUnknownNotFailedAndIsResolvedByInquiryWithoutSendingAgain`<br>`PaymentIT.afterATimeoutTheRequestIsSentAgainOnlyIfTheServiceNeverSawItAndWithTheSameKey`<br>`SoapPaymentAdapterTest.aReadTimeoutMeansTheOutcomeIsUnknown`<br>`SimulatorIT.aSlowPaymentTimesOutForTheCallerButIsFoundByInquiryAndNeverDuplicated` |
| 9 | A failed payment can be retried up to a limit; a rejected one cannot | PASSED | `PaymentIT.aFailedPaymentCanBeRetriedAsANewAttemptWithItsOwnKey`<br>`PaymentIT.retriesStopAtTheAttemptLimit`<br>`PaymentIT.aRejectedPaymentIsFinalAndCannotBeRetried`<br>`PaymentHardeningIT.simultaneousRetriesOfOneFailedPaymentSendItOnce` |
| 10 | SOAP messages are validated against the contract and XML attacks (XXE) are refused in both directions | PASSED | `SimulatorIT.requestsThatBreakTheSchemaAreRejectedBeforeAnyProcessing`<br>`SimulatorIT.aDoctypeWithAnExternalEntityIsRefusedAndNothingIsReadOrRecorded`<br>`SoapClientXxeTest.anExternalEntityInTheResponseNeverReadsALocalFile`<br>`SoapPaymentAdapterTest.aResponseThatBreaksTheSchemaIsRefused`<br>`SoapPaymentAdapterTest.aRequestThatBreaksTheSchemaNeverLeaves` |
| 11 | Reconciliation produces all six statuses and changes no invoice or payment | PASSED | `ReconciliationIT.oneRunProducesAllSixStatuses`<br>`ReconciliationEngineTest.oneRunCanProduceAllSixStatuses`<br>`ReconciliationEngineTest.duplicateBillsWinOverEveryOtherRuleAndUnpaidCopiesAreReportedToo` |
| 12 | A discrepancy is reviewed with a required note, and every external call is logged with secrets masked | PASSED | `ReconciliationIT.aDiscrepancyIsReviewedWithANoteAndNothingElseChanges`<br>`OdooSyncIT.everyOdooCallIsInTheIntegrationLogUnderTheRequestsCorrelationId`<br>`PayloadMaskerTest.masksSecretsInJson`<br>`PayloadMaskerTest.masksAuthorizationHeadersAndBearerTokens` |

All twelve cases are proven.

## Backend code coverage

Unit and integration tests together (JaCoCo), without the JAXB classes generated from the XSD: **91% of lines, 72% of branches** (2483 of 2735 lines).

| Package | Lines | Branches |
| --- | ---: | ---: |
| `com.finbridge` | 33% | n/a |
| `c.f.auth` | 100% | 100% |
| `c.f.auth.dto` | 100% | n/a |
| `c.f.common` | 98% | 89% |
| `c.f.config` | 65% | 29% |
| `c.f.dashboard` | 82% | 71% |
| `c.f.exception` | 74% | 50% |
| `c.f.integration.external` | 63% | 8% |
| `c.f.integration.monitoring` | 88% | 57% |
| `c.f.integration.odoo` | 89% | 65% |
| `c.f.integration.odoo.history` | 98% | n/a |
| `c.f.integration.odoo.sync` | 90% | 81% |
| `c.f.integration.settings` | 96% | 79% |
| `c.f.integration.soap` | 87% | 65% |
| `c.f.invoice` | 98% | 78% |
| `c.f.invoice.dto` | 100% | n/a |
| `c.f.logging` | 96% | 92% |
| `c.f.payment` | 92% | 72% |
| `c.f.payment.dto` | 70% | n/a |
| `c.f.reconciliation` | 97% | 82% |
| `c.f.security` | 98% | 81% |
| `c.f.supplier` | 85% | 62% |
| `c.f.user` | 83% | 72% |
| `c.f.user.dto` | 100% | n/a |

The frontend and the simulator have no coverage measurement.

## Backend unit tests (`mvn test`)

| Test class | Tests | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| `AuthServiceTest` | 7 | 7 | 0 | 0 | 7.1 |
| `PageQueryTest` | 7 | 7 | 0 | 0 | 0.1 |
| `OdooJson2AdapterTest` | 8 | 8 | 0 | 0 | 1.6 |
| `SoapClientXxeTest` | 3 | 3 | 0 | 0 | 2.4 |
| `SoapPaymentAdapterTest` | 9 | 9 | 0 | 0 | 0.6 |
| `InvoiceSpecificationsTest` | 2 | 2 | 0 | 0 | 0.0 |
| `InvoiceSyncServiceTest` | 8 | 8 | 0 | 0 | 0.4 |
| `CorrelationIdFilterTest` | 3 | 3 | 0 | 0 | 0.0 |
| `PayloadMaskerTest` | 6 | 6 | 0 | 0 | 0.1 |
| `ReconciliationEngineTest` | 14 | 14 | 0 | 0 | 0.1 |
| `JwtAuthenticationFilterTest` | 4 | 4 | 0 | 0 | 0.2 |
| `JwtServiceTest` | 5 | 5 | 0 | 0 | 0.0 |
| `UserServiceTest` | 6 | 6 | 0 | 0 | 0.2 |

## Backend integration tests on MySQL 8.4 in Docker (`mvn verify`)

| Test class | Tests | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| `AuthIT` | 9 | 9 | 0 | 0 | 53.5 |
| `DashboardAndSettingsIT` | 7 | 7 | 0 | 0 | 6.7 |
| `InvoiceIT` | 11 | 11 | 0 | 0 | 2.9 |
| `OdooNotConfiguredIT` | 3 | 3 | 0 | 0 | 0.5 |
| `OdooSyncIT` | 13 | 13 | 0 | 0 | 14.6 |
| `PaymentHardeningIT` | 5 | 5 | 0 | 0 | 3.7 |
| `PaymentIT` | 16 | 16 | 0 | 0 | 11.1 |
| `RbacIT` | 7 | 7 | 0 | 0 | 1.2 |
| `ReconciliationIT` | 8 | 8 | 0 | 0 | 5.6 |
| `SchemaIT` | 5 | 5 | 0 | 0 | 0.1 |
| `SecurityHardeningIT` | 10 | 10 | 0 | 0 | 3.0 |
| `OdooLiveIT` | 3 | 0 | 0 | 3 | 0.0 |

## Payment simulator over real HTTP (`mvn verify`)

| Test class | Tests | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| `SimulatorIT` | 17 | 17 | 0 | 0 | 17.2 |

## Frontend unit tests (`npm test`)

| Test class | Tests | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| `lib.test.ts` | 11 | 11 | 0 | 0 | 0.0 |
| `api.test.ts` | 7 | 7 | 0 | 0 | 0.0 |

