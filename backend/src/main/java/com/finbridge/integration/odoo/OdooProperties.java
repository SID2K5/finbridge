package com.finbridge.integration.odoo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Typed view of "finbridge.odoo.*". The API key is the only secret; it comes from the
 * ODOO_API_KEY environment variable and is allowed to be empty, in which case the
 * application starts and reports Odoo as not configured instead of refusing to start.
 */
@Validated
@ConfigurationProperties(prefix = "finbridge.odoo")
public record OdooProperties(
        @NotBlank String baseUrl,
        @NotBlank String database,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(10) int maxAttempts,
        @NotNull Duration retryBackoff,
        @Min(1) @Max(500) int pageSize) {

    public boolean hasApiKey() {
        return StringUtils.hasText(apiKey);
    }

    /** Never print the key, even by accident through a log statement or an actuator endpoint. */
    @Override
    public String toString() {
        return "OdooProperties[baseUrl=" + baseUrl + ", database=" + database
                + ", apiKey=" + (hasApiKey() ? "configured" : "missing") + "]";
    }
}
