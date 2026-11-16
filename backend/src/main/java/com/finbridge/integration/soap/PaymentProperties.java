package com.finbridge.integration.soap;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** Typed view of "finbridge.payment.*". The simulator key is the only secret and may be empty. */
@Validated
@ConfigurationProperties(prefix = "finbridge.payment")
public record PaymentProperties(
        @NotBlank String wsUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(10) int maxAttempts,
        @NotBlank String simulatorUrl,
        String simulatorApiKey) {

    public boolean hasSimulatorApiKey() {
        return StringUtils.hasText(simulatorApiKey);
    }

    @Override
    public String toString() {
        return "PaymentProperties[wsUrl=" + wsUrl + ", simulatorUrl=" + simulatorUrl
                + ", simulatorApiKey=" + (hasSimulatorApiKey() ? "configured" : "missing") + "]";
    }
}
