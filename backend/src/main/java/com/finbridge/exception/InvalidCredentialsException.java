package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/** 401: login failed. The message never says whether the email exists. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", message);
    }
}
