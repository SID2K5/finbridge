package com.finbridge.support;

import com.finbridge.config.AppProperties;
import java.util.List;

/** Builds AppProperties for unit tests without starting Spring. */
public final class TestProperties {

    public static final String JWT_SECRET = "unit-test-secret-unit-test-secret-0123456789";

    private TestProperties() {
    }

    public static AppProperties defaults() {
        return withJwt(JWT_SECRET, 60);
    }

    public static AppProperties withJwt(String secret, long expirationMinutes) {
        return new AppProperties(
                new AppProperties.Security(
                        new AppProperties.Jwt(secret, expirationMinutes),
                        new AppProperties.Login(5, 15),
                        List.of("http://localhost:5173"),
                        4,
                        false),
                new AppProperties.Bootstrap("", "", ""));
    }
}
