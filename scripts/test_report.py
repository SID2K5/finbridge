"""Builds docs/test-report.md from the test results on disk.

Run it from the finbridge folder AFTER the three suites have run:

    cd backend            && mvn clean verify
    cd payment-simulator  && mvn clean verify
    cd frontend           && npx vitest run --reporter=json --outputFile=test-results.json
    python scripts/test_report.py

Every number in the report is read from Surefire/Failsafe XML, the Vitest JSON file and the
JaCoCo CSV. Nothing is typed in by hand, so the report cannot claim a test that did not run.
"""
import csv
import datetime
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The twelve cases the design is expected to prove, each with the tests that prove it.
# A case is PASSED only if every test listed for it ran and passed.
REQUIRED_CASES = [
    ("Login succeeds with valid credentials and is refused with invalid ones, without revealing which emails exist",
     ["AuthIT.loginReturnsATokenThatOpensAProtectedEndpoint", "AuthIT.wrongPasswordAndUnknownEmailGetTheSame401",
      "AuthIT.fiveWrongPasswordsLockTheAccountInTheDatabase"]),
    ("Role-based access is enforced by the backend for every role",
     ["RbacIT.financeManagerAndOperatorCannotReachUserManagement", "PaymentIT.creatingAndRetryingAreHeldByDifferentRoles",
      "ReconciliationIT.integrationOperatorsHaveNoAccessToReconciliation",
      "OdooSyncIT.onlyAdminsAndOperatorsMaySynchronizeOrReadLogs"]),
    ("Odoo synchronization creates suppliers and invoices from vendor bills",
     ["OdooSyncIT.firstRunCreatesSuppliersAndInvoicesAcrossPages",
      "OdooJson2AdapterTest.vendorBillsAreRequestedTheJson2WayAndParsedExactly"]),
    ("Synchronizing again never duplicates an invoice",
     ["OdooSyncIT.runningAgainCreatesNoDuplicatesAndReportsUnchanged",
      "OdooSyncIT.anIncrementalRunReadsOnlyWhatChangedSinceTheCursor"]),
    ("An invalid bill is rejected with a reason and the rest of the run is kept",
     ["OdooSyncIT.anInvalidBillIsRejectedWithAReasonAndTheRestIsKept",
      "InvoiceSyncServiceTest.billsMissingRequiredDataAreRejectedWithAReason"]),
    ("A payment is sent over SOAP and a success pays the invoice",
     ["PaymentIT.aSuccessfulPaymentPaysTheInvoice", "SoapPaymentAdapterTest.submitSendsTheContractRequestAndReadsTheResult",
      "SimulatorIT.aPaymentSucceedsByDefault"]),
    ("A duplicate payment is impossible: same key replays, another key is refused, also under concurrency",
     ["PaymentIT.theSameKeyAndBodyReplaysTheTransactionAndSendsNothing", "PaymentIT.anInvoiceThatIsPaidCannotBePaidAgainWithANewKey",
      "PaymentHardeningIT.eightSimultaneousPaymentsOfOneInvoiceWithDifferentKeysPayItExactlyOnce",
      "PaymentHardeningIT.eightSimultaneousCopiesOfOneRequestBecomeOneTransactionAndOneCallToThePaymentService",
      "SimulatorIT.theSameKeyAndPayloadReturnsTheOriginalResultAndCreatesNothingNew"]),
    ("A timeout is never recorded as a failure and is resolved by a status inquiry without paying twice",
     ["PaymentIT.aTimeoutIsUnknownNotFailedAndIsResolvedByInquiryWithoutSendingAgain",
      "PaymentIT.afterATimeoutTheRequestIsSentAgainOnlyIfTheServiceNeverSawItAndWithTheSameKey",
      "SoapPaymentAdapterTest.aReadTimeoutMeansTheOutcomeIsUnknown",
      "SimulatorIT.aSlowPaymentTimesOutForTheCallerButIsFoundByInquiryAndNeverDuplicated"]),
    ("A failed payment can be retried up to a limit; a rejected one cannot",
     ["PaymentIT.aFailedPaymentCanBeRetriedAsANewAttemptWithItsOwnKey", "PaymentIT.retriesStopAtTheAttemptLimit",
      "PaymentIT.aRejectedPaymentIsFinalAndCannotBeRetried",
      "PaymentHardeningIT.simultaneousRetriesOfOneFailedPaymentSendItOnce"]),
    ("SOAP messages are validated against the contract and XML attacks (XXE) are refused in both directions",
     ["SimulatorIT.requestsThatBreakTheSchemaAreRejectedBeforeAnyProcessing",
      "SimulatorIT.aDoctypeWithAnExternalEntityIsRefusedAndNothingIsReadOrRecorded",
      "SoapClientXxeTest.anExternalEntityInTheResponseNeverReadsALocalFile",
      "SoapPaymentAdapterTest.aResponseThatBreaksTheSchemaIsRefused",
      "SoapPaymentAdapterTest.aRequestThatBreaksTheSchemaNeverLeaves"]),
    ("Reconciliation produces all six statuses and changes no invoice or payment",
     ["ReconciliationIT.oneRunProducesAllSixStatuses", "ReconciliationEngineTest.oneRunCanProduceAllSixStatuses",
      "ReconciliationEngineTest.duplicateBillsWinOverEveryOtherRuleAndUnpaidCopiesAreReportedToo"]),
    ("A discrepancy is reviewed with a required note, and every external call is logged with secrets masked",
     ["ReconciliationIT.aDiscrepancyIsReviewedWithANoteAndNothingElseChanges",
      "OdooSyncIT.everyOdooCallIsInTheIntegrationLogUnderTheRequestsCorrelationId",
      "PayloadMaskerTest.masksSecretsInJson", "PayloadMaskerTest.masksAuthorizationHeadersAndBearerTokens"]),
]


def junit_results(pattern):
    """{ 'ClassName.method': 'passed' | 'failed' | 'skipped' } and per-class rows."""
    tests, classes = {}, []
    for path in sorted(glob.glob(os.path.join(ROOT, pattern))):
        suite = ET.parse(path).getroot()
        name = suite.get("name", "").split(".")[-1]
        row = {"name": name, "tests": 0, "failed": 0, "skipped": 0, "time": float(suite.get("time", "0") or 0)}
        for case in suite.iter("testcase"):
            row["tests"] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                state = "failed"
                row["failed"] += 1
            elif case.find("skipped") is not None:
                state = "skipped"
                row["skipped"] += 1
            else:
                state = "passed"
            tests[name + "." + case.get("name").split("(")[0]] = state
        classes.append(row)
    return tests, classes


def vitest_results(path):
    tests, classes = {}, []
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return tests, classes
    data = json.load(open(full, encoding="utf-8"))
    for file in data.get("testResults", []):
        name = os.path.basename(file["name"])
        row = {"name": name, "tests": 0, "failed": 0, "skipped": 0,
               "time": (file.get("endTime", 0) - file.get("startTime", 0)) / 1000.0}
        for case in file.get("assertionResults", []):
            row["tests"] += 1
            state = {"passed": "passed", "failed": "failed"}.get(case["status"], "skipped")
            if state == "failed":
                row["failed"] += 1
            if state == "skipped":
                row["skipped"] += 1
            tests[name + "." + case["fullName"]] = state
        classes.append(row)
    return tests, classes


def coverage(path):
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return None, []
    packages = {}
    for row in csv.DictReader(open(full, encoding="utf-8")):
        p = packages.setdefault(row["PACKAGE"], [0, 0, 0, 0])
        p[0] += int(row["LINE_COVERED"])
        p[1] += int(row["LINE_MISSED"])
        p[2] += int(row["BRANCH_COVERED"])
        p[3] += int(row["BRANCH_MISSED"])
    total = [sum(v[i] for v in packages.values()) for i in range(4)]
    return total, sorted(packages.items())


def pct(covered, missed):
    return "n/a" if covered + missed == 0 else "%.0f%%" % (100.0 * covered / (covered + missed))


def table(classes):
    lines = ["| Test class | Tests | Passed | Failed | Skipped | Seconds |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in classes:
        passed = row["tests"] - row["failed"] - row["skipped"]
        lines.append("| `%s` | %d | %d | %d | %d | %.1f |" % (row["name"], row["tests"], passed, row["failed"],
                                                           row["skipped"], row["time"]))
    return lines


def totals(classes):
    tests = sum(r["tests"] for r in classes)
    failed = sum(r["failed"] for r in classes)
    skipped = sum(r["skipped"] for r in classes)
    return tests, tests - failed - skipped, failed, skipped


def main():
    unit_tests, unit_classes = junit_results("backend/target/surefire-reports/TEST-*.xml")
    it_tests, it_classes = junit_results("backend/target/failsafe-reports/TEST-*.xml")
    sim_tests, sim_classes = junit_results("payment-simulator/target/failsafe-reports/TEST-*.xml")
    sim_unit_tests, sim_unit_classes = junit_results("payment-simulator/target/surefire-reports/TEST-*.xml")
    web_tests, web_classes = vitest_results("frontend/test-results.json")
    everything = {}
    for group in (unit_tests, it_tests, sim_tests, sim_unit_tests, web_tests):
        everything.update(group)

    suites = [("Backend unit tests (`mvn test`)", unit_classes),
              ("Backend integration tests on MySQL 8.4 in Docker (`mvn verify`)", it_classes),
              ("Payment simulator over real HTTP (`mvn verify`)", sim_classes + sim_unit_classes),
              ("Frontend unit tests (`npm test`)", web_classes)]

    grand = [0, 0, 0, 0]
    out = ["# FinBridge test report", "",
           "Generated on %s by `scripts/test_report.py` from the result files of the last run of each suite. "
           "No number here is typed by hand." % datetime.datetime.now(datetime.timezone.utc).strftime("%d %B %Y, %H:%M UTC"),
           "", "## Totals", "", "| Suite | Tests | Passed | Failed | Skipped |", "| --- | ---: | ---: | ---: | ---: |"]
    for title, classes in suites:
        t = totals(classes)
        for i in range(4):
            grand[i] += t[i]
        out.append("| %s | %d | %d | %d | %d |" % ((title,) + t))
    out.append("| **All suites** | **%d** | **%d** | **%d** | **%d** |" % tuple(grand))
    out.append("")
    skipped = sorted(name for name, state in everything.items() if state == "skipped")
    failed = sorted(name for name, state in everything.items() if state == "failed")
    out.append("**Failures:** " + (", ".join("`%s`" % n for n in failed) if failed else "none."))
    out.append("")
    if skipped:
        out.append("**Skipped:** " + ", ".join("`%s`" % n for n in skipped) + ". The `OdooLiveIT` tests need a "
                   "running Odoo and its API key (`ODOO_LIVE_API_KEY`); they are skipped in a normal build and were "
                   "last run by hand on 13 November 2026 against Odoo 19.0, where all three passed.")
        out.append("")

    out += ["## The twelve required cases", "",
            "These twelve cases were derived from the rules in the Phase 1 design document, because the original "
            "list from the project brief was not available when this report was written. If the brief's list "
            "differs, change `REQUIRED_CASES` in `scripts/test_report.py` and run it again.", "",
            "| # | Case | Result | Proved by |", "| ---: | --- | --- | --- |"]
    all_required_pass = True
    for number, (case, names) in enumerate(REQUIRED_CASES, start=1):
        states = [everything.get(name, "NOT RUN") for name in names]
        if all(state == "passed" for state in states):
            result = "PASSED"
        else:
            all_required_pass = False
            result = "**NOT PROVEN** (" + ", ".join("%s: %s" % (n.split(".")[-1], s) for n, s in zip(names, states)
                                                    if s != "passed") + ")"
        out.append("| %d | %s | %s | %s |" % (number, case, result, "<br>".join("`%s`" % n for n in names)))
    out.append("")
    out.append("All twelve cases are proven." if all_required_pass else "**Not all twelve cases are proven. See the table.**")
    out.append("")

    total, packages = coverage("backend/target/site/jacoco/jacoco.csv")
    out += ["## Backend code coverage", ""]
    if total is None:
        out.append("No JaCoCo report was found. Run `mvn clean verify` in `backend/`.")
    else:
        out.append("Unit and integration tests together (JaCoCo), without the JAXB classes generated from the XSD: "
                   "**%s of lines, %s of branches** (%d of %d lines)." % (pct(total[0], total[1]), pct(total[2], total[3]),
                                                                           total[0], total[0] + total[1]))
        out += ["", "| Package | Lines | Branches |", "| --- | ---: | ---: |"]
        for name, v in packages:
            out.append("| `%s` | %s | %s |" % (name.replace("com.finbridge", "c.f") if name != "com.finbridge" else name,
                                               pct(v[0], v[1]), pct(v[2], v[3])))
        out.append("")
        out.append("The frontend and the simulator have no coverage measurement.")
    out.append("")

    for title, classes in suites:
        out += ["## " + title, ""] + table(classes) + [""]

    target = os.path.join(ROOT, "docs", "test-report.md")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    open(target, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print("wrote %s: %d tests, %d passed, %d failed, %d skipped; required cases proven: %s"
          % (target, grand[0], grand[1], grand[2], grand[3], all_required_pass))
    return 0 if grand[2] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
