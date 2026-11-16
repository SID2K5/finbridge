package com.finbridge.user.dto;

import jakarta.validation.constraints.Size;

/** Partial update: a null field is left unchanged. */
public record UpdateUserRequest(
        @Size(min = 1, max = 120) String fullName,
        Boolean enabled) {
}
