package com.tridung.caloriesdetect.dto.response;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String fullName,
        String role,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

}
