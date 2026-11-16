package com.finbridge.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed view of the "finbridge.*" settings in application.yml.
 * The application refuses to start if a required value is missing or too weak.
 */
@Validated
@ConfigurationProperties(prefix = "finbridge")
public record AppProperties(@NotNull @Valid Security security, Bootstrap bootstrap) {

    public record Security(
            @NotNull @Valid Jwt jwt,
            @NotNull @Valid Login login,
            @NotNull List<String> corsAllowedOrigins,
            @Min(4) @Max(16) int bcryptStrength,
            boolean publicApiDocs) {
    }

    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String secret,
            @Min(1) long expirationMinutes) {
    }

    public record Login(@Min(1) int maxFailedAttempts, @Min(1) long lockMinutes) {
    }

    public record Bootstrap(String adminEmail, String adminPassword, String adminFullName) {
    }
}
