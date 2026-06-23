package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.dto.request.LoginRequest;
import com.tridung.caloriesdetect.dto.request.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.LoginResponse;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;
import com.tridung.caloriesdetect.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication and JWT operations")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Login",
            description = "Authenticate using email and password, then return a JWT access token"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Login successful",
            content = @Content(schema = @Schema(
                    implementation = com.tridung.caloriesdetect.common.response.ApiResponse.class
            ))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Invalid email or password",
            content = @Content(examples = @ExampleObject(
                    value = """
                            {
                              "success": false,
                              "code": 10003,
                              "message": "Unauthorized"
                            }
                            """
            ))
    )
    public com.tridung.caloriesdetect.common.response.ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return com.tridung.caloriesdetect.common.response.ApiResponse.<LoginResponse>builder()
                .success(true)
                .code(HttpStatus.OK.value())
                .message("Login successful")
                .data(authService.login(request))
                .build();
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(
            summary = "Register",
            description = "Create a new active user account with the USER role"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Account created",
            content = @Content(schema = @Schema(
                    implementation = com.tridung.caloriesdetect.common.response.ApiResponse.class
            ))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid request or email already exists",
            content = @Content(examples = @ExampleObject(
                    value = """
                            {
                              "success": false,
                              "code": 10001,
                              "message": "User already exists"
                            }
                            """
            ))
    )
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public com.tridung.caloriesdetect.common.response.ApiResponse<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return com.tridung.caloriesdetect.common.response.ApiResponse.<RegisterResponse>builder()
                .success(true)
                .code(HttpStatus.CREATED.value())
                .message("Registration successful")
                .data(authService.register(request))
                .build();
    }
}
