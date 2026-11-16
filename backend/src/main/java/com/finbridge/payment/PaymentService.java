package com.finbridge.payment;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.InvalidRequestException;
import com.finbridge.exception.UpstreamException;
import com.finbridge.integration.soap.PaymentGateway;
import com.finbridge.integration.soap.PaymentGatewayException;
import com.finbridge.payment.dto.PaymentResponse;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The payment state machine. This class is deliberately NOT transactional: it alternates
 * short database transactions (PaymentStore) with network calls (PaymentGateway).
 *
 *   create        CREATED, then SENT, then whatever the payment service answers
 *   status-check  PENDING or TIMEOUT_UNKNOWN, resolved by a status inquiry
 *   retry         FAILED, sent again as a new attempt (limited); TIMEOUT_UNKNOWN, inquiry first
 *
 * Three rules it never breaks:
 *   1. The transaction row exists before the first byte is sent.
 *   2. A lost answer is TIMEOUT_UNKNOWN, never FAILED. Only a status inquiry may resolve it,
 *      and the request is sent again only when the service says it never saw it.
 *   3. SUCCESS and REJECTED are final.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private final PaymentStore store;
    private final PaymentGateway gateway;

    public PaymentService(PaymentStore store, PaymentGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /** The result of a create call; replayed is true when the key had already been used for this request. */
    public record CreateResult(PaymentResponse payment, boolean replayed) {
    }

    public CreateResult create(Long invoiceId, String idempotencyKey, Long userId) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey.trim()).matches()) {
            throw new InvalidRequestException("The Idempotency-Key header is required: 8 to 64 characters from "
                    + "letters, digits, '.', '_', ':' and '-' (a UUID is ideal)");
        }
        String key = idempotencyKey.trim();
        String requestHash = PaymentStore.sha256("invoiceId=" + invoiceId);

        PaymentStore.Created created;
        try {
            created = store.createOrReplay(invoiceId, key, requestHash, userId);
        } catch (DataIntegrityViolationException raceOnTheUniqueKey) {
            // Two identical requests arrived together; the other one inserted first.
            Optional<PaymentStore.Created> winner = store.findReplay(key, requestHash);
            created = winner.orElseThrow(() -> raceOnTheUniqueKey);
        }
        if (created.replayed()) {
            return new CreateResult(created.payment(), true);
        }
        return new CreateResult(send(created.payment().id(), true), false);
    }

    public PaymentResponse statusCheck(Long id) {
        PaymentStore.PaymentTransactionSnapshot tx = store.snapshot(id);
        if (tx.status() != PaymentStatus.PENDING && tx.status() != PaymentStatus.TIMEOUT_UNKNOWN) {
            throw new BusinessRuleException("A status check is only possible while a payment is PENDING or "
                    + "TIMEOUT_UNKNOWN; this one is " + tx.status());
        }
        return inquire(tx);
    }

    public PaymentResponse retry(Long id) {
        PaymentStore.PaymentTransactionSnapshot tx = store.snapshot(id);
        if (tx.status() == PaymentStatus.TIMEOUT_UNKNOWN) {
            // Never resend blindly after a lost answer: ask first.
            return inquire(tx);
        }
        if (tx.status() != PaymentStatus.FAILED) {
            throw new BusinessRuleException("Only a FAILED or TIMEOUT_UNKNOWN payment can be retried; this one is "
                    + tx.status());
        }
        return transmit(id, store.startRetry(id));
    }

    private PaymentResponse inquire(PaymentStore.PaymentTransactionSnapshot tx) {
        try {
            PaymentGateway.Outcome outcome = gateway.inquire(tx.transactionReference(),
                    new PaymentGateway.CallContext(tx.invoiceId(), tx.id()));
            store.applyOutcome(tx.id(), outcome);
            return store.view(tx.id());
        } catch (PaymentGatewayException ex) {
            if (ex.isFault(PaymentGatewayException.UNKNOWN_TRANSACTION)) {
                // The service never received it, so sending the same attempt again cannot pay twice.
                log.info("Payment {} is unknown to the payment service; sending the same attempt again",
                        tx.transactionReference());
                return send(tx.id(), false);
            }
            // The inquiry itself failed: nothing is known and nothing changes.
            if (ex.getKind() == PaymentGatewayException.Kind.TIMEOUT) {
                throw UpstreamException.timeout("The payment service did not answer the status inquiry. "
                        + "The payment is unchanged; try again shortly");
            }
            throw UpstreamException.error("The status inquiry failed (" + ex.getErrorCode()
                    + "). The payment is unchanged; try again shortly");
        }
    }

    /** Marks the transaction SENT, calls the payment service once, and records what came back. */
    private PaymentResponse send(Long id, boolean newAttempt) {
        return transmit(id, store.markSent(id, newAttempt));
    }

    /** Sends a request whose transaction is already marked SENT, and records what came back. */
    private PaymentResponse transmit(Long id, PaymentStore.Dispatch dispatch) {
        try {
            PaymentGateway.Outcome outcome = gateway.submit(dispatch.instruction(), dispatch.context());
            store.applyOutcome(id, outcome);
        } catch (PaymentGatewayException ex) {
            switch (ex.getKind()) {
                case TIMEOUT, BAD_RESPONSE -> store.markTimeoutUnknown(id, ex.getErrorCode() + ": " + ex.getMessage());
                case UNREACHABLE -> store.markFailed(id, ex.getErrorCode() + ": the request was not sent");
                case FAULT -> {
                    if (ex.isFault(PaymentGatewayException.IDEMPOTENCY_CONFLICT)) {
                        log.error("DEFECT: the payment service reports an idempotency conflict for {}. "
                                + "FinBridge sent a key it had already used with different data",
                                dispatch.instruction().transactionReference());
                    }
                    store.markFailed(id, ex.getErrorCode() + ": " + ex.getMessage());
                }
            }
        } catch (RuntimeException unexpected) {
            // The request may have left. Without proof either way, the outcome is unknown.
            log.error("Unexpected error while sending payment {}", dispatch.instruction().transactionReference(),
                    unexpected);
            store.markTimeoutUnknown(id, "INTERNAL_ERROR: the outcome of the request is unknown");
        }
        return store.view(id);
    }
}
