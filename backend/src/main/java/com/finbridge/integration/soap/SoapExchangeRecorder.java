package com.finbridge.integration.soap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.context.MessageContext;

/**
 * Keeps the exact XML that was sent and received by the current thread's SOAP call, so the
 * adapter can store both in the integration log. The request is captured before it is sent,
 * so it is available even when the call times out.
 */
public class SoapExchangeRecorder implements ClientInterceptor {

    public record Exchange(String requestXml, String responseXml) {
    }

    private static final ThreadLocal<String[]> CURRENT = new ThreadLocal<>();

    public void begin() {
        CURRENT.set(new String[2]);
    }

    public Exchange end() {
        String[] captured = CURRENT.get();
        CURRENT.remove();
        return captured == null ? new Exchange(null, null) : new Exchange(captured[0], captured[1]);
    }

    @Override
    public boolean handleRequest(MessageContext messageContext) throws WebServiceClientException {
        capture(0, messageContext.getRequest());
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) throws WebServiceClientException {
        return true;
    }

    @Override
    public boolean handleFault(MessageContext messageContext) throws WebServiceClientException {
        return true;
    }

    @Override
    public void afterCompletion(MessageContext messageContext, Exception ex) throws WebServiceClientException {
        if (messageContext.hasResponse()) {
            capture(1, messageContext.getResponse());
        }
    }

    private static void capture(int slot, WebServiceMessage message) {
        String[] captured = CURRENT.get();
        if (captured == null || message == null) {
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            captured[slot] = out.toString(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            captured[slot] = "[could not capture the message: " + ex.getClass().getSimpleName() + "]";
        }
    }
}
