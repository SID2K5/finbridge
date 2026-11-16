package com.finbridge.security;

import com.finbridge.user.RoleName;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads "Authorization: Bearer <token>". If the token is valid and the user still exists
 * and is enabled, and the password was not reset after the token was issued, the request
 * is authenticated with that user's CURRENT roles from the database. Anything else leaves the request anonymous and Spring Security answers 401.
 *
 * Not a @Component on purpose: it is created in SecurityConfig, which stops Spring Boot
 * from also registering it as a plain servlet filter and running it twice.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SecurityErrorHandler errorHandler;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   SecurityErrorHandler errorHandler) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.errorHandler = errorHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                jwtService.verify(token).ifPresent(verified -> userRepository.findWithRolesById(verified.userId())
                        .filter(User::isEnabled)
                        .filter(user -> !issuedBeforeCredentialChange(verified, user))
                        .ifPresent(this::authenticate));
            } catch (DataAccessException | TransactionException ex) {
                // The user could not be loaded because the database is unreachable. That is not
                // "wrong token": answer 503 in the usual JSON envelope instead of a bare 500.
                log.error("Could not check the access token: the database is unavailable", ex);
                errorHandler.serviceUnavailable(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * True when the password was reset after this token was issued. A token's issue time has
     * whole-second precision, so the comparison is made in whole seconds.
     */
    static boolean issuedBeforeCredentialChange(JwtService.VerifiedToken token, User user) {
        Instant changed = user.getCredentialsChangedAt();
        return changed != null && token.issuedAt().isBefore(changed.truncatedTo(ChronoUnit.SECONDS));
    }

    private void authenticate(User user) {
        Set<RoleName> roles = user.getRoles().stream().map(role -> role.getName()).collect(Collectors.toSet());
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getFullName(), roles);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
