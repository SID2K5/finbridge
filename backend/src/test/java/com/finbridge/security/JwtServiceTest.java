package com.finbridge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finbridge.support.TestProperties;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void tokenIssuedByTheServiceIsAcceptedAndCarriesTheUserId() {
        JwtService service = new JwtService(TestProperties.defaults(), clockAt(NOW));

        String token = service.issueToken(42L, "ada@test.example", List.of("ADMIN"));

        assertThat(service.verifyAndGetUserId(token)).contains(42L);
        assertThat(service.timeToLiveSeconds()).isEqualTo(3600);
    }

    @Test
    void tokenIsRejectedAfterItExpires() {
        JwtService issuer = new JwtService(TestProperties.defaults(), clockAt(NOW));
        String token = issuer.issueToken(42L, "ada@test.example", List.of("ADMIN"));

        JwtService justBeforeExpiry = new JwtService(TestProperties.defaults(),
                clockAt(NOW.plus(Duration.ofMinutes(59))));
        JwtService afterExpiry = new JwtService(TestProperties.defaults(),
                clockAt(NOW.plus(Duration.ofMinutes(61))));

        assertThat(justBeforeExpiry.verifyAndGetUserId(token)).contains(42L);
        assertThat(afterExpiry.verifyAndGetUserId(token)).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService attacker = new JwtService(
                TestProperties.withJwt("a-completely-different-secret-0123456789abcdef", 60), clockAt(NOW));
        JwtService service = new JwtService(TestProperties.defaults(), clockAt(NOW));

        String forged = attacker.issueToken(1L, "admin@test.example", List.of("ADMIN"));

        assertThat(service.verifyAndGetUserId(forged)).isEmpty();
    }

    @Test
    void textThatIsNotATokenIsRejected() {
        JwtService service = new JwtService(TestProperties.defaults(), clockAt(NOW));

        assertThat(service.verifyAndGetUserId("not-a-jwt")).isEmpty();
        assertThat(service.verifyAndGetUserId("")).isEmpty();
    }

    @Test
    void secretShorterThan256BitsIsRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtService(TestProperties.withJwt("too-short", 60), clockAt(NOW)))
                .isInstanceOf(WeakKeyException.class);
    }
}
