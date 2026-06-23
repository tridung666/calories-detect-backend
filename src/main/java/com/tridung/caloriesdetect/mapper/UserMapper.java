package com.tridung.caloriesdetect.mapper;

import com.tridung.caloriesdetect.dto.request.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;
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
    @Mapping(target = "password", source = "encodedPassword")
    @Mapping(target = "fullName", expression = "java(request.fullName().trim())")
    @Mapping(target = "role", constant = "USER")
    @Mapping(target = "status", constant = "ACTIVE")
    User toEntity(RegisterRequest request, String encodedPassword);

    RegisterResponse toRegisterResponse(User user);

    @Named("normalizeEmail")
    default String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
