package com.tridung.caloriesdetect.dto.response.auth;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
