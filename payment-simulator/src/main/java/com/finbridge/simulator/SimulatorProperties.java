package com.finbridge.simulator;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        @NotBlank @Size(min = 16, message = "SIM_API_KEY must be at least 16 characters") String apiKey,
        @NotNull Duration slowDelay,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal shortPayFactor) {

    @Override
    public String toString() {
        return "SimulatorProperties[apiKey=configured, slowDelay=" + slowDelay + "]";
    }
}
