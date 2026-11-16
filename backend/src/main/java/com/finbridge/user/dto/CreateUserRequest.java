package com.finbridge.user.dto;

import com.finbridge.user.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 190) String email,
        @NotBlank @Size(max = 120) String fullName,
        // BCrypt only reads the first 72 bytes, so longer passwords are refused rather than silently cut.
        @NotBlank @Size(min = 12, max = 72, message = "password must be 12 to 72 characters") String password,
        @NotEmpty(message = "at least one role is required") Set<RoleName> roles) {
}
