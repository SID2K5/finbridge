package com.finbridge.payment.dto;

import com.finbridge.logging.IntegrationLog;
import java.time.Instant;
import java.util.List;

/** A payment with every call made to the payment service for it, oldest first. */
public record PaymentDetailResponse(PaymentResponse payment, List<Attempt> attempts) {

    /**
     * One SOAP call. The XML payloads are not included here; admins and operators read
     * them through GET /api/integrations/logs/{logId}.
     */
    public record Attempt(Long logId, Instant at, String operation, String outcome, Integer httpStatus,
                          int durationMs, String errorCode, String errorDescription) {

        public static Attempt from(IntegrationLog log) {
            return new Attempt(log.getId(), log.getCreatedAt(), log.getOperation(), log.getOutcome(),
                    log.getHttpStatus(), log.getDurationMs(), log.getErrorCode(), log.getErrorDescription());
        }
    }
}
