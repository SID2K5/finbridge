package com.finbridge.payment;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.ConflictException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.settings.SettingsService;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoicePaymentService;
import com.finbridge.invoice.InvoiceStatus;
import com.finbridge.invoice.SyncStatus;
import com.finbridge.payment.dto.PaymentResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database step of a payment, each in its own short transaction.
 *
 * createOrReplay and prepareRetry run at READ COMMITTED on purpose. MySQL's default
 * (REPEATABLE READ) fixes what a transaction can see at its first read; a request that
 * waited for the invoice lock would then still not see the payment the other request had
 * just committed, and could create a second one. At READ COMMITTED the check made after
 * the lock sees it. No network call is
 * ever made from inside one of these methods, so a slow payment service can never hold a
 * database lock.
 */
@Component
public class PaymentStore {

    /** What createOrReplay did. */
    public record Created(PaymentResponse payment, boolean replayed) {
    }

    /** Everything needed to send one request, read while the row was marked SENT. */
    public record Dispatch(PaymentGateway.Instruction instruction, PaymentGateway.CallContext context) {
    }

    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";   // no 0/O, 1/I
    private static final DateTimeFormatter REFERENCE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentRepository repository;
    private final InvoicePaymentService invoices;
    private final SettingsService settings;
    private final Clock clock;

    public PaymentStore(PaymentRepository repository, InvoicePaymentService invoices, SettingsService settings,
                        Clock clock) {
        this.repository = repository;
        this.invoices = invoices;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Created> findReplay(String idempotencyKey, String requestHash) {
        return repository.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ConflictException(
                        "This Idempotency-Key was already used for a different request. Use a new key");
            }
            return new Created(PaymentResponse.from(existing), true);
        });
    }

    /**
     * Creates the transaction in status CREATED, or returns the one this key already made.
     * The invoice row is locked first, so two different keys for the same invoice cannot
     * both pass the "no payment in flight" check.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created createOrReplay(Long invoiceId, String idempotencyKey, String requestHash, Long userId) {
        Optional<Created> replay = findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Invoice invoice = invoices.lock(invoiceId);
        // Another request with the same key may have committed while this one waited for the lock.
        replay = findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (invoice.getSyncStatus() == SyncStatus.STALE) {
            throw new BusinessRuleException("Invoice " + invoice.getInvoiceNumber()
                    + " changed in Odoo after an earlier payment and is marked STALE. Review it before paying");
        }
        if (invoice.getTotalAmount().signum() <= 0) {
            throw new BusinessRuleException("Invoice " + invoice.getInvoiceNumber() + " has nothing to pay");
        }
        if (repository.existsByInvoiceIdAndStatusIn(invoiceId, PaymentStatus.BLOCKING)) {
            throw new ConflictException("Invoice " + invoice.getInvoiceNumber()
                    + " already has a payment that succeeded or is still in progress");
        }
        Instant now = clock.instant();
        PaymentTransaction tx = repository.saveAndFlush(new PaymentTransaction(newReference(now), idempotencyKey,
                requestHash, invoice, userId, now));
        invoices.setStatus(invoice, InvoiceStatus.PAYMENT_IN_PROGRESS);
        return new Created(PaymentResponse.from(tx), false);
    }

    /**
     * Starts a retry of a FAILED payment: checks that it may be sent again and marks it SENT,
     * in one transaction, under the invoice lock. The lock is taken BEFORE the payment is
     * read, so of two simultaneous retries the second one reads a payment that is already
     * SENT and is refused; it can never send a second request.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Dispatch startRetry(Long id) {
        Long invoiceId = repository.findInvoiceIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + id + " was not found"));
        Invoice invoice = invoices.lock(invoiceId);
        PaymentTransaction tx = load(id);
        if (tx.getStatus() != PaymentStatus.FAILED) {
            throw new BusinessRuleException("Only a FAILED payment can be retried; this one is " + tx.getStatus());
        }
        int maxAttempts = settings.intValue(SettingsService.Setting.PAYMENT_MAX_ATTEMPTS);
        if (tx.getAttemptCount() >= maxAttempts) {
            throw new BusinessRuleException("The attempt limit of " + maxAttempts
                    + " is reached for " + tx.getTransactionReference() + ". Create a new payment instead");
        }
        if (repository.existsByInvoiceIdAndStatusInAndIdNot(invoice.getId(), PaymentStatus.BLOCKING, id)) {
            throw new ConflictException("Invoice " + invoice.getInvoiceNumber()
                    + " already has another payment that succeeded or is still in progress");
        }
        invoices.setStatus(invoice, InvoiceStatus.PAYMENT_IN_PROGRESS);
        return dispatch(tx, true);
    }

    @Transactional
    public Dispatch markSent(Long id, boolean newAttempt) {
        return dispatch(load(id), newAttempt);
    }

    private Dispatch dispatch(PaymentTransaction tx, boolean newAttempt) {
        tx.markSent(newAttempt, clock.instant());
        PaymentGateway.Instruction instruction = new PaymentGateway.Instruction(tx.getTransactionReference(),
                tx.getInvoice().getInvoiceNumber(), PaymentSnapshot.supplierReference(tx),
                tx.getRequestedAmount(), tx.getCurrency(), soapKey(tx.getIdempotencyKey(), tx.getAttemptCount()));
        return new Dispatch(instruction, new PaymentGateway.CallContext(tx.getInvoice().getId(), tx.getId()));
    }

    @Transactional
    public void applyOutcome(Long id, PaymentGateway.Outcome outcome) {
        PaymentTransaction tx = load(id);
        if (tx.getStatus().isFinal()) {
            return;   // SUCCESS and REJECTED never change again
        }
        PaymentStatus status = PaymentStatus.valueOf(outcome.status().name());
        tx.applyOutcome(status, outcome.externalTransactionId(), outcome.processedAmount(), outcome.failureReason(),
                clock.instant());
        invoices.setStatus(tx.getInvoice(), switch (status) {
            case SUCCESS -> InvoiceStatus.PAID;
            case FAILED, REJECTED -> InvoiceStatus.PAYMENT_FAILED;
            default -> InvoiceStatus.PAYMENT_IN_PROGRESS;
        });
    }

    /** The answer was lost. The invoice stays "in progress": it may well have been paid. */
    @Transactional
    public void markTimeoutUnknown(Long id, String reason) {
        PaymentTransaction tx = load(id);
        if (!tx.getStatus().isFinal()) {
            tx.markTimeoutUnknown(reason);
        }
    }

    @Transactional
    public void markFailed(Long id, String reason) {
        PaymentTransaction tx = load(id);
        if (!tx.getStatus().isFinal()) {
            tx.markFailed(reason, clock.instant());
            invoices.setStatus(tx.getInvoice(), InvoiceStatus.PAYMENT_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse view(Long id) {
        return PaymentResponse.from(load(id));
    }

    @Transactional(readOnly = true)
    public PaymentTransactionSnapshot snapshot(Long id) {
        PaymentTransaction tx = load(id);
        return new PaymentTransactionSnapshot(tx.getId(), tx.getInvoice().getId(), tx.getTransactionReference(),
                tx.getStatus());
    }

    public record PaymentTransactionSnapshot(Long id, Long invoiceId, String transactionReference,
                                             PaymentStatus status) {
    }

    private PaymentTransaction load(Long id) {
        return repository.findWithInvoiceById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + id + " was not found"));
    }

    /** FB-20261110-7K2M9QXA: the UTC date plus eight random characters. */
    private static String newReference(Instant now) {
        StringBuilder random = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            random.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return "FB-" + REFERENCE_DATE.format(now) + "-" + random;
    }

    /**
     * The key sent to the payment service. Attempt 1 uses the caller's key unchanged. A later
     * attempt (an operator retrying a FAILED payment) is a new instruction and gets its own
     * key, derived from the first, so the service processes it instead of replaying the
     * failure. Resending the SAME attempt after a lost answer reuses the same key.
     */
    static String soapKey(String idempotencyKey, int attempt) {
        if (attempt <= 1) {
            return idempotencyKey;
        }
        return sha256(idempotencyKey + "#attempt-" + attempt);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
