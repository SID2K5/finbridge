package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/**
 * 502 or 504: an external system FinBridge depends on failed or did not answer in time,
 * and nothing was changed in FinBridge because of it.
 */
public class UpstreamException extends ApiException {

    private UpstreamException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static UpstreamException error(String message) {
        return new UpstreamException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", message);
    }

    public static UpstreamException timeout(String message) {
        return new UpstreamException(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", message);
    }
}
