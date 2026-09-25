package com.tridung.caloriesdetect.mapper;

import com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.dto.response.auth.UserResponse;
import com.tridung.caloriesdetect.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.Locale;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", source = "request.email", qualifiedByName = "normalizeEmail")
    @Mapping(target = "fullName", expression = "java(request.fullName().trim())")
    @Mapping(target = "role", constant = "USER")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "emailVerified", constant = "false")
    User toEntity(RegisterRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", source = "request.email", qualifiedByName = "normalizeEmail")
    @Mapping(target = "fullName", expression = "java(request.fullName().trim())")
    @Mapping(target = "role", source = "request.role")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "emailVerified", constant = "false")
    User toEntity(AdminUserRequest request);

    RegisterResponse toRegisterResponse(User user);

    @Named("normalizeEmail")
    default String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    @Mapping(target = "status", expression = "java(user.getStatus().name())")
    UserResponse toUserResponse(User user);
}
