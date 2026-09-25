package com.tridung.caloriesdetect.dto.response.auth;

import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;

public record RegisterResponse(
        Long id,
        String email,
        String fullName,
        UserRole role,
        UserStatus status,
        Boolean emailVerified
) {
}
