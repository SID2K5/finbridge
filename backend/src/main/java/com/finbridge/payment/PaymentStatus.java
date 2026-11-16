package com.finbridge.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of one payment transaction.
 *
 *   CREATED          stored, not yet sent
 *   SENT             a SOAP request is on its way
 *   SUCCESS          paid                              (final)
 *   REJECTED         refused by the payment service    (final)
 *   PENDING          accepted, not settled yet         (resolved by a status inquiry)
 *   FAILED           not paid; may be sent again by an operator, up to the attempt limit
 *   TIMEOUT_UNKNOWN  sent, no answer: it may or may not have been paid. Never treated as a
 *                    failure. The only way out is a status inquiry.
 */
public enum PaymentStatus {
    CREATED,
    SENT,
    SUCCESS,
    PENDING,
    FAILED,
    REJECTED,
    TIMEOUT_UNKNOWN;

    /** While a transaction is in one of these, its invoice must not get a second payment. */
    public static final Set<PaymentStatus> BLOCKING = EnumSet.of(CREATED, SENT, PENDING, TIMEOUT_UNKNOWN, SUCCESS);

    public boolean isFinal() {
        return this == SUCCESS || this == REJECTED;
    }
}
