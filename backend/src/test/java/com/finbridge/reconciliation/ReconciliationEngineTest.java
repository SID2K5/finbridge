package com.finbridge.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finbridge.reconciliation.ReconciliationEngine.DuplicateGroup;
import com.finbridge.reconciliation.ReconciliationEngine.External;
import com.finbridge.reconciliation.ReconciliationEngine.Finding;
import com.finbridge.reconciliation.ReconciliationEngine.Internal;
import com.finbridge.reconciliation.ReconciliationRecord.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The six rules and their order, with no database and no network. */
class ReconciliationEngineTest {

    private static final Instant AT = Instant.parse("2026-11-10T09:30:00Z");

    private static Internal payment(long id, String reference, String invoice, String amount, String status) {
        return new Internal(id, id * 10, invoice, "SUP-42", reference, "SIM-" + reference, new BigDecimal(amount),
                "SUCCESS".equals(status) ? new BigDecimal(amount) : null, "INR", status,
                "FAILED".equals(status) ? "PROCESSING_ERROR" : null, AT);
    }

    private static External entry(String reference, String invoice, String amount, String status) {
        return new External("SIM-" + reference, reference, invoice, "SUP-42", new BigDecimal(amount), "INR", status,
                AT);
    }

    private static List<Finding> run(List<Internal> payments, List<External> statement, List<DuplicateGroup> groups) {
        return ReconciliationEngine.reconcile(payments, statement, statement, groups);
    }

    private static Finding only(List<Finding> findings) {
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    @Test
    void successOnBothSidesWithEqualValuesIsMatched() {
        Finding finding = only(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "100.0000", "SUCCESS")), List.of()));

        assertThat(finding.status()).isEqualTo(Status.MATCHED);
        assertThat(finding.description()).isNull();
        assertThat(finding.expectedAmount()).isEqualByComparingTo("100");
        assertThat(finding.actualAmount()).isEqualByComparingTo("100");
    }

    @Test
    void rejectedOnBothSidesIsMatchedBecauseItIsFinalAndAgreed() {
        assertThat(only(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "REJECTED")),
                List.of(entry("FB-1", "BILL/1", "0", "REJECTED")), List.of())).status()).isEqualTo(Status.MATCHED);
    }

    @Test
    void agreedButUnsettledPaymentsNeedReview() {
        for (String status : List.of("PENDING", "FAILED")) {
            Finding finding = only(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", status)),
                    List.of(entry("FB-1", "BILL/1", "0", status)), List.of()));

            assertThat(finding.status()).isEqualTo(Status.PENDING_REVIEW);
            assertThat(finding.description()).contains(status);
        }
    }

    @Test
    void aShortPaymentIsAMismatchThatNamesTheAmounts() {
        Finding finding = only(run(List.of(payment(1, "FB-1", "BILL/1", "1000.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "900.00", "SUCCESS")), List.of()));

        assertThat(finding.status()).isEqualTo(Status.MISMATCH);
        assertThat(finding.description()).isEqualTo(
                "amount: FinBridge requested 1000, payment service settled 900 (difference 100)");
    }

    @Test
    void aTimeoutThatWasActuallyPaidIsAStatusMismatch() {
        Finding finding = only(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "TIMEOUT_UNKNOWN")),
                List.of(entry("FB-1", "BILL/1", "100.00", "SUCCESS")), List.of()));

        assertThat(finding.status()).isEqualTo(Status.MISMATCH);
        assertThat(finding.description()).isEqualTo("status: FinBridge TIMEOUT_UNKNOWN, payment service SUCCESS");
    }

    @Test
    void everyDifferingFieldIsListedInAFixedOrder() {
        Internal ours = payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS");
        External theirs = new External("SIM-FB-1", "FB-1", "BILL/9", "SUP-7", new BigDecimal("90"), "USD", "PENDING",
                AT);

        assertThat(ReconciliationEngine.differences(ours, theirs)).containsExactly(
                "invoice reference: FinBridge BILL/1, payment service BILL/9",
                "supplier reference: FinBridge SUP-42, payment service SUP-7",
                "currency: FinBridge INR, payment service USD",
                "status: FinBridge SUCCESS, payment service PENDING");
    }

    @Test
    void aSentPaymentWithNoStatementEntryIsMissingExternally() {
        Finding finding = only(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")), List.of(), List.of()));

        assertThat(finding.status()).isEqualTo(Status.MISSING_EXTERNAL_RECORD);
        assertThat(finding.actualAmount()).isNull();
        assertThat(finding.description()).contains("FB-1", "no entry");
    }

    @Test
    void aPaymentThatWasNeverSentIsNotReconciled() {
        assertThat(run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "CREATED")), List.of(), List.of())).isEmpty();
    }

    @Test
    void aStatementEntryWithNoPaymentIsMissingInternally() {
        Finding finding = only(run(List.of(), List.of(entry("EXT-9", "BILL/7", "77.50", "SUCCESS")), List.of()));

        assertThat(finding.status()).isEqualTo(Status.MISSING_INTERNAL_RECORD);
        assertThat(finding.internal()).isNull();
        assertThat(finding.invoiceId()).isNull();
        assertThat(finding.expectedAmount()).isNull();
        assertThat(finding.actualAmount()).isEqualByComparingTo("77.50");
        assertThat(finding.description()).contains("EXT-9", "BILL/7", "77.5");
    }

    @Test
    void onlyEntriesOfThePeriodCanBeMissingInternallyButAllAreUsedForMatching() {
        External neighbourDay = entry("FB-1", "BILL/1", "100.00", "SUCCESS");
        External strayNeighbour = entry("EXT-OTHER-DAY", "BILL/8", "5.00", "SUCCESS");

        List<Finding> findings = ReconciliationEngine.reconcile(
                List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")),
                List.of(neighbourDay, strayNeighbour), List.of(), List.of());

        assertThat(only(findings).status()).isEqualTo(Status.MATCHED);
    }

    @Test
    void twoSuccessfulEntriesForOneInvoiceAreDuplicatesOnBothLines() {
        List<Finding> findings = run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "100.00", "SUCCESS"), entry("EXT-2", "BILL/1", "100.00", "SUCCESS")),
                List.of());

        assertThat(findings).extracting(Finding::status).containsExactly(Status.DUPLICATE, Status.DUPLICATE);
        assertThat(findings.get(0).description()).contains("2 successful payments", "BILL/1");
        assertThat(findings.get(1).internal()).isNull();
    }

    @Test
    void aSuccessAndAFailureForOneInvoiceAreNotADuplicate() {
        List<Finding> findings = run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "100.00", "SUCCESS"), entry("EXT-2", "BILL/1", "0", "FAILED")),
                List.of());

        assertThat(findings).extracting(Finding::status)
                .containsExactly(Status.MATCHED, Status.MISSING_INTERNAL_RECORD);
    }

    @Test
    void duplicateBillsWinOverEveryOtherRuleAndUnpaidCopiesAreReportedToo() {
        DuplicateGroup group = new DuplicateGroup("INV-778", List.of(
                new DuplicateGroup.Member(10L, "BILL/1"), new DuplicateGroup.Member(555L, "BILL/2")));

        // The paid copy would otherwise be MATCHED; rule 1 comes first.
        List<Finding> findings = run(List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "100.00", "SUCCESS")), List.of(group));

        assertThat(findings).extracting(Finding::status).containsExactly(Status.DUPLICATE, Status.DUPLICATE);
        assertThat(findings.get(0).internal().transactionReference()).isEqualTo("FB-1");
        assertThat(findings.get(0).description()).contains("BILL/1", "INV-778", "BILL/2");
        assertThat(findings.get(1).invoiceId()).isEqualTo(555L);
        assertThat(findings.get(1).internal()).isNull();
        assertThat(findings.get(1).description()).contains("BILL/2", "no payment in this period");
    }

    @Test
    void oneRunCanProduceAllSixStatuses() {
        DuplicateGroup group = new DuplicateGroup("DUP-REF", List.of(
                new DuplicateGroup.Member(60L, "BILL/6"), new DuplicateGroup.Member(61L, "BILL/6B")));

        List<Finding> findings = run(
                List.of(payment(1, "FB-1", "BILL/1", "100.00", "SUCCESS"),
                        payment(2, "FB-2", "BILL/2", "100.00", "SUCCESS"),
                        payment(3, "FB-3", "BILL/3", "100.00", "PENDING"),
                        payment(4, "FB-4", "BILL/4", "100.00", "SUCCESS"),
                        payment(6, "FB-6", "BILL/6", "100.00", "SUCCESS")),
                List.of(entry("FB-1", "BILL/1", "100.00", "SUCCESS"),
                        entry("FB-2", "BILL/2", "90.00", "SUCCESS"),
                        entry("FB-3", "BILL/3", "0", "PENDING"),
                        entry("FB-6", "BILL/6", "100.00", "SUCCESS"),
                        entry("EXT-5", "BILL/5", "50.00", "SUCCESS")),
                List.of(group));

        assertThat(findings).extracting(Finding::status).containsExactlyInAnyOrder(
                Status.MATCHED, Status.MISMATCH, Status.PENDING_REVIEW, Status.MISSING_EXTERNAL_RECORD,
                Status.DUPLICATE, Status.MISSING_INTERNAL_RECORD, Status.DUPLICATE);
    }
}
