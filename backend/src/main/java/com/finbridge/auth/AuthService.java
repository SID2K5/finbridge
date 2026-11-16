package com.finbridge.auth;

import com.finbridge.auth.dto.LoginRequest;
import com.finbridge.auth.dto.LoginResponse;
import com.finbridge.config.AppProperties;
import com.finbridge.exception.InvalidCredentialsException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.security.JwtService;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import com.finbridge.user.UserService;
import com.finbridge.user.dto.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String GENERIC_FAILURE = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties.Login loginPolicy;
    private final Clock clock;
    /** Hash checked when the email is unknown, so that case takes as long as a wrong password. */
    private final String timingEqualizerHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AppProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginPolicy = properties.security().login();
        this.clock = clock;
        this.timingEqualizerHash = passwordEncoder.encode("finbridge-timing-equalizer");
    }

    /**
     * Every failure (unknown email, wrong password, disabled or locked account) returns the
     * same 401 message, so the endpoint cannot be used to discover which emails exist.
     * The failed-attempt counter must survive the exception, hence noRollbackFor.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginResponse login(LoginRequest request) {
        Instant now = clock.instant();
        Optional<User> found = userRepository.findByEmail(UserService.normalizeEmail(request.email()));

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), timingEqualizerHash);
            throw new InvalidCredentialsException(GENERIC_FAILURE);
        }
        User user = found.get();

        if (!user.isEnabled() || user.isLockedAt(now)) {
            passwordEncoder.matches(request.password(), timingEqualizerHash);
            log.warn("Login refused for user id {}: account is {}", user.getId(),
                    user.isEnabled() ? "locked" : "disabled");
            throw new InvalidCredentialsException(GENERIC_FAILURE);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.registerFailedLogin(now, loginPolicy.maxFailedAttempts(), loginPolicy.lockMinutes());
            if (user.isLockedAt(now)) {
                log.warn("User id {} locked for {} minutes after repeated failed logins",
                        user.getId(), loginPolicy.lockMinutes());
            }
            throw new InvalidCredentialsException(GENERIC_FAILURE);
        }

        user.registerSuccessfulLogin(now);
        UserResponse userResponse = UserResponse.from(user);
        String token = jwtService.issueToken(user.getId(), user.getEmail(), userResponse.roles());
        return new LoginResponse(token, "Bearer", jwtService.timeToLiveSeconds(), userResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return userRepository.findWithRolesById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User was not found"));
    }
}
