package com.finbridge.security;

import com.finbridge.user.RoleName;
import java.util.Set;

/** The logged-in user, available in controllers through @AuthenticationPrincipal. */
public record AuthenticatedUser(Long id, String email, String fullName, Set<RoleName> roles) {
}
