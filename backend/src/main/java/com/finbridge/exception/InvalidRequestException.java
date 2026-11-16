package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/** 400: a request parameter has an unacceptable value. */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
