package com.finbridge.auth.dto;

import com.finbridge.user.dto.UserResponse;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
