package com.tridung.caloriesdetect.dto.request.auth;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "Set revoked for refreshTOken")
        String refreshToken
) {}
