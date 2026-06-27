package com.tridung.caloriesdetect.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "Set revoked for refreshTOken")
        String refreshToken
) {}
