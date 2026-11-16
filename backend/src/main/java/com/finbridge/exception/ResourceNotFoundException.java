package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/** 404: the requested record does not exist. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
