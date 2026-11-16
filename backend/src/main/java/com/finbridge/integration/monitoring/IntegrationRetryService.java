package com.finbridge.integration.monitoring;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.integration.odoo.sync.SyncRunResponse;
import com.finbridge.integration.odoo.sync.SyncService;
import com.finbridge.logging.IntegrationLog;
import com.finbridge.logging.IntegrationLogRepository;
import com.finbridge.payment.PaymentQueryService;
import com.finbridge.payment.PaymentService;
import com.finbridge.payment.dto.PaymentResponse;
import org.springframework.stereotype.Service;

/**
 * "Retry" on a failed integration log entry. It never replays the stored request: a logged
 * payload may be stale, and replaying a payment request blindly is exactly what the payment
 * state machine exists to prevent. Instead it starts the business operation the entry
 * belongs to, through the same service a person would use:
 *
 *   ODOO entry             a new synchronization run (reads only, safe to repeat)
 *   SOAP entry, a payment  the payment's own retry or status check, with all its rules
 *   REST entry             a new health check
 *
 * The new calls get their own log entries; they are found through the returned run or
 * payment, and are not linked back to the old entry (retry_of_log_id stays empty).
 */
@Service
public class IntegrationRetryService {

    public record RetryResult(String action, String detail, Long syncRunId, Long paymentId, String paymentStatus) {
    }

    private final IntegrationLogRepository logRepository;
    private final SyncService syncService;
    private final PaymentService paymentService;
    private final PaymentQueryService paymentQueryService;
    private final IntegrationStatusService statusService;

    public IntegrationRetryService(IntegrationLogRepository logRepository, SyncService syncService,
                                   PaymentService paymentService, PaymentQueryService paymentQueryService,
                                   IntegrationStatusService statusService) {
        this.logRepository = logRepository;
        this.syncService = syncService;
        this.paymentService = paymentService;
        this.paymentQueryService = paymentQueryService;
        this.statusService = statusService;
    }

    public RetryResult retry(Long logId, Long userId) {
        IntegrationLog entry = logRepository.findById(logId)
                .orElseThrow(() -> new ResourceNotFoundException("Integration log " + logId + " was not found"));
        if ("SUCCESS".equals(entry.getOutcome())) {
            throw new BusinessRuleException("This call succeeded; there is nothing to retry");
        }
        if (!entry.isRetryable()) {
            throw new BusinessRuleException("This failure is not retryable (" + entry.getErrorCode()
                    + "): sending the same thing again would fail the same way. Fix the cause first");
        }
        return switch (entry.getIntegrationType()) {
            case "ODOO" -> {
                SyncRunResponse run = syncService.start(userId, false);
                yield new RetryResult("SYNC_STARTED", "Synchronization run " + run.id() + " was started",
                        run.id(), null, null);
            }
            case "SOAP" -> retryPayment(entry);
            default -> {
                IntegrationStatusService.HealthCheckResponse check = statusService.check();
                yield new RetryResult("HEALTH_CHECKED", check.rest().ok()
                        ? "The simulator answered"
                        : "The simulator still does not answer: " + check.rest().errorCode(), null, null, null);
            }
        };
    }

    private RetryResult retryPayment(IntegrationLog entry) {
        Long paymentId = entry.getPaymentTransactionId();
        if (paymentId == null) {
            throw new BusinessRuleException("This SOAP call does not belong to a payment. "
                    + "Run the reconciliation or the health check again instead");
        }
        String status = paymentQueryService.get(paymentId).status();
        PaymentResponse result = switch (status) {
            case "FAILED", "TIMEOUT_UNKNOWN" -> paymentService.retry(paymentId);
            case "PENDING" -> paymentService.statusCheck(paymentId);
            default -> throw new BusinessRuleException("Payment " + paymentId + " is already " + status
                    + "; there is nothing to retry");
        };
        return new RetryResult("PAYMENT_RETRIED", "Payment " + result.transactionReference() + " is now "
                + result.status(), null, result.id(), result.status());
    }
}
