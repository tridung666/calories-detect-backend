package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.dto.request.LoginRequest;
import com.tridung.caloriesdetect.dto.request.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.LoginResponse;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;
import com.tridung.caloriesdetect.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tridung.caloriesdetect.common.response.BaseResponse;

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
    @ApiResponse(
            responseCode = "200",
            description = "Login successful",
            content = @Content(schema = @Schema(
                    implementation = BaseResponse.class
            ))
    )
    @ApiResponse(
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
    public BaseResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return BaseResponse.success(authService.login(request));
    }

    @PostMapping("/refresh-token")
    @SecurityRequirements
    @Operation(
            summary = "Refresh token",
            description = "Issue a new access token and refresh token using a valid refresh token"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Token refreshed successfully",
            content = @Content(schema = @Schema(
                    implementation = BaseResponse.class
            ))
    )
    @ApiResponse(
            responseCode = "401",
            description = "Invalid, expired, or revoked refresh token",
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
    public BaseResponse<LoginResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return BaseResponse.success(authService.refreshToken(request));
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(
            summary = "Register",
            description = "Create a new active user account with the USER role"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Account created",
            content = @Content(schema = @Schema(
                    implementation = BaseResponse.class
            ))
    )
    @ApiResponse(
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
    public BaseResponse<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return BaseResponse.success(authService.register(request));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    public BaseResponse<String> logout(
            @Valid @RequestBody LogoutRequest request
    ) {
        authService.logout(request);
        return BaseResponse.success("Logout successfull");
    }
}
