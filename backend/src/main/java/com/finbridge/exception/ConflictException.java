package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/** 409: the request clashes with existing data, for example a duplicate email. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
