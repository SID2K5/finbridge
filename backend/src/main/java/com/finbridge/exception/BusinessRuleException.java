package com.finbridge.exception;

import org.springframework.http.HttpStatus;

/** 422: the request is well formed but breaks a business rule. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", message);
    }
}
