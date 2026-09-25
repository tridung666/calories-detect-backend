package com.tridung.caloriesdetect.dto.response.auth;

public record LoginResponse(
        String accessToken,
        @com.fasterxml.jackson.annotation.JsonIgnore
        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
