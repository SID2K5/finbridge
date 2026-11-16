package com.finbridge.logging;

/**
 * What a gateway reports about one outbound call. Build it with {@link #success} or
 * {@link #failure}; IntegrationLogService adds the correlation id, masks the payloads and stores it.
 */
public record IntegrationLogEntry(
        Type type,
        String operation,
        String requestReference,
        Outcome outcome,
        Integer httpStatus,
        long durationMs,
        String errorCode,
        String errorDescription,
        Long invoiceId,
        Long paymentTransactionId,
        String requestPayload,
        String responsePayload,
        boolean retryable) {

    public enum Type { ODOO, REST, SOAP }

    public enum Outcome { SUCCESS, FAILURE, TIMEOUT }

    /** The same entry, linked to the invoice and payment transaction the call was made for. */
    public IntegrationLogEntry forRecords(Long invoiceId, Long paymentTransactionId) {
        return new IntegrationLogEntry(type, operation, requestReference, outcome, httpStatus, durationMs,
                errorCode, errorDescription, invoiceId, paymentTransactionId, requestPayload, responsePayload,
                retryable);
    }

    public static IntegrationLogEntry success(Type type, String operation, String requestReference,
                                              Integer httpStatus, long durationMs,
                                              String requestPayload, String responsePayload) {
        return new IntegrationLogEntry(type, operation, requestReference, Outcome.SUCCESS, httpStatus, durationMs,
                null, null, null, null, requestPayload, responsePayload, false);
    }

    public static IntegrationLogEntry failure(Type type, String operation, String requestReference,
                                              Outcome outcome, Integer httpStatus, long durationMs,
                                              String errorCode, String errorDescription,
                                              String requestPayload, String responsePayload, boolean retryable) {
        return new IntegrationLogEntry(type, operation, requestReference, outcome, httpStatus, durationMs,
                errorCode, errorDescription, null, null, requestPayload, responsePayload, retryable);
    }
}
