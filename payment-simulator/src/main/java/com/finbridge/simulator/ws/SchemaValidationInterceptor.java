package com.finbridge.simulator.ws;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.xml.sax.SAXParseException;

/** Schema validation whose failure is reported with the contract's INVALID_REQUEST error code. */
public class SchemaValidationInterceptor extends PayloadValidatingInterceptor {

    private final Jaxb2Marshaller marshaller;

    public SchemaValidationInterceptor(Jaxb2Marshaller marshaller) {
        this.marshaller = marshaller;
    }

    @Override
    protected boolean handleRequestValidationErrors(MessageContext messageContext, SAXParseException[] errors) {
        String reasons = Arrays.stream(errors).limit(3).map(SAXParseException::getMessage)
                .collect(Collectors.joining("; "));
        FaultWriter.write(messageContext, marshaller, false, "INVALID_REQUEST",
                "The request does not match the payments schema: " + reasons);
        return false;   // stop: the endpoint is never called with an invalid request
    }
}
