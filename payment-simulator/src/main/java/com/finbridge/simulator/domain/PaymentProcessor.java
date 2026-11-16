package com.finbridge.simulator.domain;

import com.finbridge.simulator.SimulatorProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The simulated provider's behaviour. Rules a real provider would also follow:
 *
 *   - Idempotency: the same IdempotencyKey with the same payload returns the original
 *     outcome and creates nothing. The same key with a different payload is a conflict.
 *   - A TransactionReference identifies one payment. Submitting it again under a new key is
 *     accepted only after it FAILED (a retry); otherwise it is a conflict.
 *   - An OUTAGE records nothing, so a caller may safely send the same request again.
 */
@Service
public class PaymentProcessor {

    /** The result of a submission, and how long the endpoint must wait before answering. */
    public record Submission(SimPayment payment, Duration delayBeforeAnswering) {
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private final SimPaymentRepository payments;
    private final ScenarioRepository scenarios;
    private final SimulatorProperties properties;
    private final Clock clock;

    public PaymentProcessor(SimPaymentRepository payments, ScenarioRepository scenarios,
                            SimulatorProperties properties, Clock clock) {
        this.payments = payments;
        this.scenarios = scenarios;
        this.properties = properties;
        this.clock = clock;
    }

    public Scenario scenarioFor(String invoiceReference) {
        return scenarios.findById(invoiceReference).map(ScenarioSetting::getScenario).orElse(Scenario.SUCCESS);
    }

    /** One submission at a time, so two concurrent copies of a request can never both be processed. */
    @Transactional
    public synchronized Submission submit(String transactionReference, String invoiceReference,
                                          String supplierReference, BigDecimal requestedAmount, String currency,
                                          String idempotencyKey) {
        Scenario scenario = scenarioFor(invoiceReference);
        if (scenario == Scenario.OUTAGE) {
            throw PaymentFaultException.server("SERVICE_UNAVAILABLE",
                    "The payment service is temporarily unavailable. Nothing was recorded");
        }
        BigDecimal amount = requestedAmount.setScale(4, RoundingMode.UNNECESSARY);
        String hash = hash(transactionReference, invoiceReference, supplierReference, amount, currency);

        Optional<SimPayment> sameKey = payments.findByIdempotencyKey(idempotencyKey);
        if (sameKey.isPresent()) {
            if (!sameKey.get().getPayloadHash().equals(hash)) {
                throw PaymentFaultException.client("IDEMPOTENCY_CONFLICT",
                        "IdempotencyKey was already used with a different payload");
            }
            return new Submission(sameKey.get(), Duration.ZERO);   // replay: original outcome, no delay
        }

        Optional<SimPayment> sameReference = payments.findByTransactionReference(transactionReference);
        SimPayment payment;
        if (sameReference.isPresent()) {
            payment = sameReference.get();
            boolean retryOfFailure = "FAILED".equals(payment.getStatus()) && payment.getPayloadHash().equals(hash);
            if (!retryOfFailure) {
                throw PaymentFaultException.client("IDEMPOTENCY_CONFLICT",
                        "TransactionReference was already used by a payment that is " + payment.getStatus());
            }
        } else {
            payment = new SimPayment(newExternalId(), transactionReference, invoiceReference, supplierReference,
                    amount, currency);
        }

        Instant now = clock.instant();
        switch (scenario) {
            case PENDING_THEN_SUCCESS -> payment.process(idempotencyKey, hash, scenario, "PENDING", ZERO, null, now);
            case FAILED -> payment.process(idempotencyKey, hash, scenario, "FAILED", ZERO, "PROCESSING_ERROR", now);
            case REJECTED -> payment.process(idempotencyKey, hash, scenario, "REJECTED", ZERO, "ACCOUNT_BLOCKED", now);
            case SHORT_PAY -> payment.process(idempotencyKey, hash, scenario, "SUCCESS",
                    amount.multiply(properties.shortPayFactor()).setScale(4, RoundingMode.HALF_UP), null, now);
            default -> payment.process(idempotencyKey, hash, scenario, "SUCCESS", amount, null, now);
        }
        payments.save(payment);
        Duration delay = scenario == Scenario.SLOW_SUCCESS ? properties.slowDelay() : Duration.ZERO;
        return new Submission(payment, delay);
    }

    @Transactional
    public SimPayment inquire(String transactionReference) {
        SimPayment payment = payments.findByTransactionReference(transactionReference)
                .orElseThrow(() -> PaymentFaultException.client("UNKNOWN_TRANSACTION",
                        "No payment was received with TransactionReference " + transactionReference));
        payment.countInquiry();
        if (payment.getScenario() == Scenario.PENDING_THEN_SUCCESS && "PENDING".equals(payment.getStatus())
                && payment.getInquiryCount() >= 2) {
            payment.settle(clock.instant());
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public List<SimPayment> statement(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw PaymentFaultException.client("INVALID_REQUEST", "FromDate must not be after ToDate");
        }
        return payments.findByResponseTimestampGreaterThanEqualAndResponseTimestampLessThanOrderById(
                from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    /** A settled payment FinBridge never asked for: the MISSING_INTERNAL_RECORD case. */
    @Transactional
    public SimPayment createOrphan(String invoiceReference, String supplierReference, BigDecimal amount,
                                   String currency) {
        String reference = "EXT-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
        BigDecimal scaled = amount.setScale(4, RoundingMode.HALF_UP);
        SimPayment payment = new SimPayment(newExternalId(), reference, invoiceReference, supplierReference,
                scaled, currency);
        payment.process("orphan-" + UUID.randomUUID(), hash(reference, invoiceReference, supplierReference, scaled,
                currency), Scenario.SUCCESS, "SUCCESS", scaled, null, clock.instant());
        payment.markOrphan();
        return payments.save(payment);
    }

    private static String newExternalId() {
        return "SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /** RequestTimestamp is deliberately not part of the hash: a resend carries a newer one. */
    private static String hash(String transactionReference, String invoiceReference, String supplierReference,
                               BigDecimal amount, String currency) {
        String text = String.join("|", transactionReference, invoiceReference, supplierReference,
                amount.toPlainString(), currency);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
