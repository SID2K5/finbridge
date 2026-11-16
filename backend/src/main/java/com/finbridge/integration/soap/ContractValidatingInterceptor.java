package com.finbridge.integration.soap;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.support.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.context.MessageContext;
import org.xml.sax.SAXParseException;

/**
 * Validates both directions against the contract and STOPS on a violation. Spring's own
 * client interceptor only logs an invalid response and lets it through; for payments that
 * is not acceptable, so both cases raise an exception here:
 *
 *   invalid request   the request is not sent at all (a FinBridge defect, found before any harm);
 *   invalid response  the answer is not trusted, and the payment's outcome is treated as unknown.
 */
public class ContractValidatingInterceptor extends PayloadValidatingInterceptor {

    /** The outgoing request broke the schema. It was not sent. */
    public static class InvalidRequestException extends WebServiceClientException {

        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** The incoming response broke the schema. */
    public static class InvalidResponseException extends WebServiceClientException {

        public InvalidResponseException(String message) {
            super(message);
        }
    }

    @Override
    protected boolean handleRequestValidationErrors(MessageContext messageContext, SAXParseException[] errors) {
        throw new InvalidRequestException("The request does not match the payments schema: " + describe(errors));
    }

    @Override
    protected boolean handleResponseValidationErrors(MessageContext messageContext, SAXParseException[] errors) {
        throw new InvalidResponseException("The response does not match the payments schema: " + describe(errors));
    }

    private static String describe(SAXParseException[] errors) {
        return Arrays.stream(errors).limit(3).map(SAXParseException::getMessage).collect(Collectors.joining("; "));
    }
}
