package com.tridung.caloriesdetect.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank
        String oldPassword,

        @NotBlank
        @Size(min = 8, max = 72)
        String newPassword,

        @NotBlank
        String confirmNewPassword
) {
}
