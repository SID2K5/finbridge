package com.finbridge.simulator.rest;

import com.finbridge.simulator.SimulatorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the REST control endpoints under /sim with the X-Sim-Api-Key header. They can
 * change how payments behave and invent settlements, so they are never open.
 * The SOAP endpoint under /ws is not covered: it is the provider's public interface.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Sim-Api-Key";

    private final byte[] expected;

    public ApiKeyFilter(SimulatorProperties properties) {
        this.expected = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/sim") || path.startsWith("/sim/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        // Constant-time comparison, so the response time says nothing about the key.
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":{\"code\":\"INVALID_API_KEY\",\"message\":\"A valid X-Sim-Api-Key header is required\"}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
