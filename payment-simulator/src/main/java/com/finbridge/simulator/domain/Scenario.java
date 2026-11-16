package com.finbridge.simulator.domain;

/** What the simulator does with a payment for a given invoice reference. SUCCESS is the default. */
public enum Scenario {
    /** Processed in full. */
    SUCCESS,
    /** PENDING on submit; SUCCESS from the second status inquiry. */
    PENDING_THEN_SUCCESS,
    /** FAILED with reason PROCESSING_ERROR. May be submitted again. */
    FAILED,
    /** REJECTED with reason ACCOUNT_BLOCKED. Final. */
    REJECTED,
    /** Processes the payment, then answers later than the client is willing to wait. */
    SLOW_SUCCESS,
    /** SUCCESS, but settles less than was requested. */
    SHORT_PAY,
    /** SERVICE_UNAVAILABLE fault; nothing is recorded. */
    OUTAGE
}
