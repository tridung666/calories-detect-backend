package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.dto.request.auth.ChangePasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.security.CustomUserDetails;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
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
                              "code": 11001,
                              "message": "Invalid email or password"
                            }
                            """
            ))
    )
    public BaseResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return BaseResponse.success(authService.login(request));
    }

    @PostMapping("/google")
    @SecurityRequirements
    @Operation(
            summary = "Login with Google",
            description = "Verify a Google ID token from the frontend, then return application JWT tokens"
    )
    public BaseResponse<LoginResponse> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest request
    ) {
        return BaseResponse.success(authService.loginWithGoogle(request));
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
                              "code": 13000,
                              "message": "Refresh token not found"
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
                              "message": "Email already exists"
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
    @Operation(
            summary = "Logout",
            description = "Revoke a valid refresh token"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Logout successful",
            content = @Content(schema = @Schema(
                    implementation = BaseResponse.class
            ))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid, expired, or revoked refresh token",
            content = @Content(examples = @ExampleObject(
                    value = """
                            {
                              "success": false,
                              "code": 13000,
                              "message": "Refresh token not found"
                            }
                            """
            ))
    )
    public BaseResponse<String> logout(
            @Valid @RequestBody LogoutRequest request
    ) {
        authService.logout(request);
        return BaseResponse.success("Logout successfull");
    }

    @PutMapping("/change-password")
    @Operation(
            summary = "Change password",
            description = "Change password for the currently authenticated user. Requires a valid Bearer JWT."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Password changed successfully",
            content = @Content(
                    schema = @Schema(implementation = BaseResponse.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "success": true,
                                      "code": 200,
                                      "message": "Success",
                                      "data": "Password changed successfully"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request body or current password is incorrect",
            content = @Content(examples = {
                    @ExampleObject(
                            name = "Current password invalid",
                            value = """
                                    {
                                      "success": false,
                                      "code": 11002,
                                      "message": "Old password not match"
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "New password too short",
                            value = """
                                    {
                                      "success": false,
                                      "code": 400,
                                      "message": "size must be between 8 and 72"
                                    }
                                    """
                    )
            })
    )
    @ApiResponse(
            responseCode = "401",
            description = "Missing or invalid Bearer token",
            content = @Content(examples = @ExampleObject(
                    value = """
                            {
                              "success": false,
                              "code": 11000,
                              "message": "Unauthorized"
                            }
                            """
            ))
    )
    public BaseResponse<String> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        authService.changePassword(userDetails.user().getId(), request);
        return BaseResponse.success("Password changed successfully");
    }
}
