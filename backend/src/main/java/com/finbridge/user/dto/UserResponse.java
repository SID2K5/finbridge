package com.finbridge.user.dto;

import com.finbridge.user.User;
import java.time.Instant;
import java.util.List;

/** What the API returns for a user. The password hash never leaves the backend. */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        boolean enabled,
        List<String> roles,
        Instant lastLoginAt,
        Instant lockedUntil,
        Instant createdAt) {

    public static UserResponse from(User user) {
        List<String> roleNames = user.getRoles().stream()
                .map(role -> role.getName().name())
                .sorted()
                .toList();
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.isEnabled(),
                roleNames, user.getLastLoginAt(), user.getLockedUntil(), user.getCreatedAt());
    }
}
