package com.tridung.caloriesdetect.dto.request.admin;

import com.tridung.caloriesdetect.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 2, max = 255)
        String fullName,

        @NotBlank
        @Size(min = 8, max = 72, message = "Password must be at least 8 characters")
        String password,

        @NotNull
        UserRole role
) {
}
