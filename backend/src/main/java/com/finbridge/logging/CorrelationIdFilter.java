package com.finbridge.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: taken from the X-Correlation-Id header when it is a
 * valid UUID, otherwise generated. The id is put in the logging context, returned as a
 * response header and included in every response body. It runs before Spring Security so
 * that 401 and 403 responses carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Only a well-formed UUID is accepted from the client, so the header cannot inject text into logs. */
    static String resolve(String headerValue) {
        if (headerValue != null) {
            try {
                return UUID.fromString(headerValue.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // fall through and generate a new one
            }
        }
        return UUID.randomUUID().toString();
    }

    public static String currentId() {
        return MDC.get(MDC_KEY);
    }
}
