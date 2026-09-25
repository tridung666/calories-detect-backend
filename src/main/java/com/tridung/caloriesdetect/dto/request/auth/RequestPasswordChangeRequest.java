package com.tridung.caloriesdetect.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestPasswordChangeRequest(
        @NotBlank @Size(max = 72) String currentPassword
) {
}
