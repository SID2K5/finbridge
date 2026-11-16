package com.finbridge.simulator.domain;

/** A request that cannot be processed at all. Becomes a SOAP fault with an ErrorCode in its detail. */
public class PaymentFaultException extends RuntimeException {

    private final String errorCode;
    private final boolean serverFault;

    private PaymentFaultException(String errorCode, String message, boolean serverFault) {
        super(message);
        this.errorCode = errorCode;
        this.serverFault = serverFault;
    }

    public static PaymentFaultException client(String errorCode, String message) {
        return new PaymentFaultException(errorCode, message, false);
    }

    public static PaymentFaultException server(String errorCode, String message) {
        return new PaymentFaultException(errorCode, message, true);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isServerFault() {
        return serverFault;
    }
}
