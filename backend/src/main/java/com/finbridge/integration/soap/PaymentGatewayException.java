package com.finbridge.integration.soap;

/**
 * A call to the payment service that produced no usable answer. The kind tells the payment
 * module the one thing it must know: was the request possibly processed?
 */
public class PaymentGatewayException extends RuntimeException {

    public enum Kind {
        /** Could not connect. The request never left, so nothing happened at the other side. */
        UNREACHABLE("PAYMENT_SERVICE_UNREACHABLE"),
        /** Sent, but no answer in time or the connection dropped. The outcome is UNKNOWN. */
        TIMEOUT("PAYMENT_TIMEOUT"),
        /** The service answered with a SOAP fault: it refused or could not process the request. */
        FAULT("PAYMENT_FAULT"),
        /** Sent and answered, but the answer is not valid against the contract. Outcome UNKNOWN. */
        BAD_RESPONSE("PAYMENT_BAD_RESPONSE");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String UNKNOWN_TRANSACTION = "UNKNOWN_TRANSACTION";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final Kind kind;
    private final String errorCode;

    public PaymentGatewayException(Kind kind, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.errorCode = errorCode != null ? errorCode : kind.code();
    }

    public Kind getKind() {
        return kind;
    }

    /** For a FAULT, the ErrorCode from the fault detail; otherwise the kind's own code. */
    public String getErrorCode() {
        return errorCode;
    }

    public boolean isFault(String code) {
        return kind == Kind.FAULT && code.equals(errorCode);
    }
}
