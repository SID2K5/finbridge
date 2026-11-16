package com.finbridge.reconciliation;

import com.finbridge.reconciliation.ReconciliationRecord.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The reconciliation rules. A pure function: no database, no network, no clock. It is
 * given FinBridge's payments, the payment service's statement and the duplicate bills, and
 * returns one finding per payment, statement entry or duplicate bill.
 *
 * Each item is classified by the FIRST rule that applies:
 *
 *   1  DUPLICATE                 the invoice shares supplier and vendor reference with another
 *                                invoice, or the statement holds two successful entries for
 *                                one invoice reference
 *   2  MISSING_EXTERNAL_RECORD   FinBridge sent a payment, the statement does not have it
 *   3  MISSING_INTERNAL_RECORD   the statement has a payment FinBridge never made
 *   4  MISMATCH                  both exist, and invoice reference, supplier reference,
 *                                currency, amount or status differ
 *   5  PENDING_REVIEW            both agree, but the payment is PENDING, FAILED or TIMEOUT_UNKNOWN
 *   6  MATCHED                   both agree, and the payment is SUCCESS or REJECTED
 */
public final class ReconciliationEngine {

    /** A FinBridge payment transaction as the rules see it. */
    public record Internal(Long id, Long invoiceId, String invoiceNumber, String supplierReference,
                           String transactionReference, String externalTransactionId, BigDecimal requestedAmount,
                           BigDecimal processedAmount, String currency, String status, String failureReason,
                           Instant requestedAt) {
    }

    /** One entry of the payment service's settlement statement. */
    public record External(String externalTransactionId, String transactionReference, String invoiceReference,
                           String supplierReference, BigDecimal processedAmount, String currency, String status,
                           Instant responseTimestamp) {
    }

    /** Invoices of one supplier that carry the same vendor reference: the same bill entered twice. */
    public record DuplicateGroup(String vendorReference, List<Member> members) {

        public record Member(Long invoiceId, String invoiceNumber) {
        }
    }

    public record Finding(Status status, Internal internal, External external, Long invoiceId,
                          BigDecimal expectedAmount, BigDecimal actualAmount, String currency,
                          String description) {
    }

    private ReconciliationEngine() {
    }

    /**
     * @param payments            FinBridge payments to reconcile (those still CREATED are ignored:
     *                            nothing was sent for them)
     * @param statement           every statement entry available for matching by reference
     * @param unmatchedCandidates the statement entries that belong to the period: only these can
     *                            be reported as MISSING_INTERNAL_RECORD
     * @param duplicateGroups     duplicate bills to report
     */
    public static List<Finding> reconcile(Collection<Internal> payments, Collection<External> statement,
                                          Collection<External> unmatchedCandidates,
                                          Collection<DuplicateGroup> duplicateGroups) {
        Map<String, External> entryByReference = new LinkedHashMap<>();
        statement.forEach(entry -> entryByReference.putIfAbsent(entry.transactionReference(), entry));

        // Rule 1b: two SUCCESS entries for the same invoice reference means it was paid twice.
        Map<String, Long> successesPerInvoice = statement.stream()
                .filter(entry -> "SUCCESS".equals(entry.status()))
                .collect(Collectors.groupingBy(External::invoiceReference, Collectors.counting()));
        Set<String> paidTwice = successesPerInvoice.entrySet().stream()
                .filter(count -> count.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());

        // Rule 1a: invoices that are duplicates of each other.
        Map<Long, DuplicateGroup> groupByInvoice = new HashMap<>();
        duplicateGroups.forEach(group -> group.members()
                .forEach(member -> groupByInvoice.put(member.invoiceId(), group)));

        List<Finding> findings = new ArrayList<>();
        Set<String> matchedReferences = new HashSet<>();
        Set<Long> reportedInvoices = new HashSet<>();

        for (Internal payment : payments) {
            if ("CREATED".equals(payment.status())) {
                continue;
            }
            External entry = entryByReference.get(payment.transactionReference());
            if (entry != null) {
                matchedReferences.add(entry.transactionReference());
            }
            reportedInvoices.add(payment.invoiceId());
            findings.add(classify(payment, entry, groupByInvoice.get(payment.invoiceId()), paidTwice,
                    successesPerInvoice));
        }

        for (External entry : unmatchedCandidates) {
            if (matchedReferences.contains(entry.transactionReference())) {
                continue;
            }
            if (paidTwice.contains(entry.invoiceReference()) && "SUCCESS".equals(entry.status())) {
                findings.add(new Finding(Status.DUPLICATE, null, entry, null, null, entry.processedAmount(),
                        entry.currency(), paidTwiceText(entry.invoiceReference(), successesPerInvoice)
                        + " This entry (" + entry.externalTransactionId() + ") has no FinBridge payment"));
            } else {
                findings.add(new Finding(Status.MISSING_INTERNAL_RECORD, null, entry, null, null,
                        entry.processedAmount(), entry.currency(),
                        "The payment service reports " + entry.status() + " payment " + entry.externalTransactionId()
                                + " (reference " + entry.transactionReference() + ", invoice "
                                + entry.invoiceReference() + ", " + plain(entry.processedAmount()) + " "
                                + entry.currency() + "), and FinBridge has no payment with that reference"));
            }
        }

        // Duplicate bills that have no payment in this run still deserve a line: catching the
        // second bill BEFORE it is paid is the whole point.
        for (DuplicateGroup group : duplicateGroups) {
            for (DuplicateGroup.Member member : group.members()) {
                if (reportedInvoices.add(member.invoiceId())) {
                    findings.add(new Finding(Status.DUPLICATE, null, null, member.invoiceId(), null, null, null,
                            duplicateBillText(member.invoiceNumber(), group) + " It has no payment in this period"));
                }
            }
        }
        return findings;
    }

    private static Finding classify(Internal payment, External entry, DuplicateGroup group, Set<String> paidTwice,
                                    Map<String, Long> successesPerInvoice) {
        BigDecimal actual = entry != null ? entry.processedAmount() : null;

        if (group != null) {
            return finding(Status.DUPLICATE, payment, entry, actual,
                    duplicateBillText(payment.invoiceNumber(), group) + " Payment " + payment.transactionReference()
                            + " is " + payment.status());
        }
        if (entry != null && paidTwice.contains(entry.invoiceReference()) && "SUCCESS".equals(entry.status())) {
            return finding(Status.DUPLICATE, payment, entry, actual,
                    paidTwiceText(entry.invoiceReference(), successesPerInvoice));
        }
        if (entry == null) {
            String hint = "FAILED".equals(payment.status())
                    ? " The payment FAILED in FinBridge (" + payment.failureReason()
                    + "), so this is expected if the request never reached the service"
                    : " Check whether the payment service lost it or was restarted";
            return finding(Status.MISSING_EXTERNAL_RECORD, payment, null, null,
                    "FinBridge payment " + payment.transactionReference() + " is " + payment.status()
                            + " and the settlement statement has no entry for it." + hint);
        }
        List<String> differences = differences(payment, entry);
        if (!differences.isEmpty()) {
            return finding(Status.MISMATCH, payment, entry, actual, String.join("; ", differences));
        }
        if ("SUCCESS".equals(payment.status()) || "REJECTED".equals(payment.status())) {
            return finding(Status.MATCHED, payment, entry, actual, null);
        }
        return finding(Status.PENDING_REVIEW, payment, entry, actual,
                "Both sides agree that the payment is " + payment.status()
                        + ("FAILED".equals(payment.status()) && payment.failureReason() != null
                        ? " (" + payment.failureReason() + ")" : "")
                        + ". It is not settled, so someone should follow it up");
    }

    /** Every field that differs, in a fixed order, each as "field: FinBridge X, payment service Y". */
    static List<String> differences(Internal payment, External entry) {
        List<String> differences = new ArrayList<>();
        if (!payment.invoiceNumber().equals(entry.invoiceReference())) {
            differences.add(differ("invoice reference", payment.invoiceNumber(), entry.invoiceReference()));
        }
        if (!payment.supplierReference().equals(entry.supplierReference())) {
            differences.add(differ("supplier reference", payment.supplierReference(), entry.supplierReference()));
        }
        if (!payment.currency().equals(entry.currency())) {
            differences.add(differ("currency", payment.currency(), entry.currency()));
        }
        // What was asked for against what was settled. Only a settled payment has an amount to compare.
        if ("SUCCESS".equals(entry.status()) && entry.processedAmount() != null
                && payment.requestedAmount().compareTo(entry.processedAmount()) != 0) {
            differences.add("amount: FinBridge requested " + plain(payment.requestedAmount())
                    + ", payment service settled " + plain(entry.processedAmount()) + " (difference "
                    + plain(payment.requestedAmount().subtract(entry.processedAmount())) + ")");
        }
        if (!payment.status().equals(entry.status())) {
            differences.add(differ("status", payment.status(), entry.status()));
        }
        return differences;
    }

    private static Finding finding(Status status, Internal payment, External entry, BigDecimal actual,
                                   String description) {
        return new Finding(status, payment, entry, payment.invoiceId(), payment.requestedAmount(), actual,
                payment.currency(), description);
    }

    private static String differ(String field, String internal, String external) {
        return field + ": FinBridge " + internal + ", payment service " + external;
    }

    private static String duplicateBillText(String invoiceNumber, DuplicateGroup group) {
        String others = group.members().stream().map(DuplicateGroup.Member::invoiceNumber)
                .filter(number -> !number.equals(invoiceNumber)).collect(Collectors.joining(", "));
        return "Invoice " + invoiceNumber + " has the same supplier and vendor reference ("
                + group.vendorReference() + ") as " + others + ": the same bill may have been entered twice.";
    }

    private static String paidTwiceText(String invoiceReference, Map<String, Long> successesPerInvoice) {
        return "The settlement statement holds " + successesPerInvoice.get(invoiceReference)
                + " successful payments for invoice reference " + invoiceReference + ": it may have been paid twice.";
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "nothing" : amount.stripTrailingZeros().toPlainString();
    }
}
