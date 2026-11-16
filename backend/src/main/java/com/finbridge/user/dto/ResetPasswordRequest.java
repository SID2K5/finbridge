package com.finbridge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(min = 12, max = 72, message = "password must be 12 to 72 characters") String newPassword) {
}
