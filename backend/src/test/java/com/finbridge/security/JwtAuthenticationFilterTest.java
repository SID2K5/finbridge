package com.finbridge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finbridge.support.TestProperties;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = new JwtService(TestProperties.defaults(), Clock.systemUTC());
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SecurityErrorHandler errorHandler =
            new SecurityErrorHandler(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository, errorHandler);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/invoices");
        request.addHeader("Authorization", "Bearer " + jwtService.issueToken(7L, "a@b.example", List.of()));
        return request;
    }

    @Test
    void aDatabaseOutageWhileCheckingTheTokenIsA503InTheJsonEnvelopeNotABare500() throws Exception {
        for (RuntimeException outage : List.of(
                new DataAccessResourceFailureException("Communications link failure"),
                new CannotCreateTransactionException("Could not open JPA EntityManager"))) {
            when(userRepository.findWithRolesById(7L)).thenThrow(outage);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(requestWithToken(), response, chain);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"")
                    .doesNotContain("Communications link", "EntityManager", "Exception");
            org.mockito.Mockito.reset(userRepository);
        }
        // The request went no further: no controller ran without knowing who was asking.
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aValidTokenOfAnEnabledUserAuthenticatesTheRequest() throws Exception {
        when(userRepository.findWithRolesById(7L)).thenReturn(Optional.of(new User("a@b.example", "A", "hash")));

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aTokenIssuedBeforeThePasswordWasResetIsIgnored() throws Exception {
        User user = new User("a@b.example", "A", "hash");
        user.changePassword("new-hash", Instant.now().plusSeconds(5));
        when(userRepository.findWithRolesById(7L)).thenReturn(Optional.of(user));

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), chain);

        // Anonymous: Spring Security will answer 401 further down the chain.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aTokenIssuedInTheSameSecondAsTheResetOrLaterIsAccepted() {
        User user = new User("a@b.example", "A", "hash");
        Instant reset = Instant.parse("2026-11-14T10:00:00.900Z");
        user.changePassword("new-hash", reset);

        assertThat(JwtAuthenticationFilter.issuedBeforeCredentialChange(
                new JwtService.VerifiedToken(7L, Instant.parse("2026-11-14T10:00:00Z")), user)).isFalse();
        assertThat(JwtAuthenticationFilter.issuedBeforeCredentialChange(
                new JwtService.VerifiedToken(7L, Instant.parse("2026-11-14T09:59:59Z")), user)).isTrue();
        assertThat(JwtAuthenticationFilter.issuedBeforeCredentialChange(
                new JwtService.VerifiedToken(7L, Instant.EPOCH), new User("x@y.example", "X", "h"))).isFalse();
    }
}
