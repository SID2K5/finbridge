package com.finbridge.auth;

import com.finbridge.auth.dto.LoginRequest;
import com.finbridge.auth.dto.LoginResponse;
import com.finbridge.common.ApiResponse;
import com.finbridge.security.AuthenticatedUser;
import com.finbridge.user.UserService;
import com.finbridge.user.dto.CreateUserRequest;
import com.finbridge.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in and receive an access token")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a user (ADMIN only; there is no public sign-up)")
    public ApiResponse<UserResponse> register(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.of(userService.create(request));
    }

    @GetMapping("/me")
    @Operation(summary = "The logged-in user and their roles")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(authService.currentUser(principal.id()));
    }
}
