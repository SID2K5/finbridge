package com.finbridge.security;

import com.finbridge.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies access tokens (JWT, HS256). The signing secret comes from the
 * environment and must be at least 32 characters (256 bits).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration timeToLive;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        AppProperties.Jwt jwt = properties.security().jwt();
        this.key = Keys.hmacShaKeyFor(jwt.secret().getBytes(StandardCharsets.UTF_8));
        this.timeToLive = Duration.ofMinutes(jwt.expirationMinutes());
        this.clock = clock;
    }

    public String issueToken(Long userId, String email, Collection<String> roles) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(timeToLive)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Returns the user id when the token has a valid signature and has not expired.
     * Roles are deliberately NOT trusted from the token: the caller reloads the user,
     * so a disabled account or a changed role takes effect on the very next request.
     */
    public Optional<Long> verifyAndGetUserId(String token) {
        return verify(token).map(VerifiedToken::userId);
    }

    /** What a valid token proves: who it was issued to, and when. */
    public record VerifiedToken(Long userId, Instant issuedAt) {
    }

    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Instant issuedAt = claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : Instant.EPOCH;
            return Optional.of(new VerifiedToken(Long.valueOf(claims.getSubject()), issuedAt));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public long timeToLiveSeconds() {
        return timeToLive.toSeconds();
    }
}
