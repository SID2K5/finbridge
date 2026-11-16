package com.finbridge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.finbridge.auth.dto.LoginRequest;
import com.finbridge.auth.dto.LoginResponse;
import com.finbridge.exception.InvalidCredentialsException;
import com.finbridge.security.JwtService;
import com.finbridge.support.TestProperties;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    private static final String EMAIL = "grace@test.example";
    private static final String PASSWORD = "Correct-Horse-9!";

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private User user;

    @BeforeEach
    void setUp() {
        user = new User(EMAIL, "Grace Hopper", passwordEncoder.encode(PASSWORD));
        ReflectionTestUtils.setField(user, "id", 7L);
    }

    private AuthService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        JwtService jwtService = new JwtService(TestProperties.defaults(), clock);
        return new AuthService(userRepository, passwordEncoder, jwtService, TestProperties.defaults(), clock);
    }

    private void userExists() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    void correctPasswordReturnsATokenAndRecordsTheLogin() {
        userExists();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JwtService jwtService = new JwtService(TestProperties.defaults(), clock);
        AuthService service = new AuthService(userRepository, passwordEncoder, jwtService,
                TestProperties.defaults(), clock);

        LoginResponse response = service.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(jwtService.verifyAndGetUserId(response.accessToken())).contains(7L);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void emailIsMatchedCaseInsensitively() {
        userExists();

        LoginResponse response = serviceAt(NOW).login(new LoginRequest("  Grace@Test.Example ", PASSWORD));

        assertThat(response.user().email()).isEqualTo(EMAIL);
    }

    @Test
    void wrongPasswordIsRefusedAndCounted() {
        userExists();

        assertThatThrownBy(() -> serviceAt(NOW).login(new LoginRequest(EMAIL, "wrong-password-1")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheCorrectPassword() {
        userExists();
        AuthService service = serviceAt(NOW);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong-password-1")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void lockExpiresAfterFifteenMinutes() {
        userExists();
        AuthService service = serviceAt(NOW);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong-password-1")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        LoginResponse response = serviceAt(NOW.plus(Duration.ofMinutes(16)))
                .login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void disabledUserIsRefused() {
        userExists();
        user.setEnabled(false);

        assertThatThrownBy(() -> serviceAt(NOW).login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknownEmailGetsExactlyTheSameMessageAsAWrongPassword() {
        userExists();
        when(userRepository.findByEmail("nobody@test.example")).thenReturn(Optional.empty());
        AuthService service = serviceAt(NOW);

        Throwable wrongPassword = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest(EMAIL, "wrong-password-1")));
        Throwable unknownEmail = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest("nobody@test.example", PASSWORD)));

        assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }
}
