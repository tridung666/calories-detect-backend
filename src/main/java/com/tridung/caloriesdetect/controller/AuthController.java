package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.dto.request.auth.RequestPasswordChangeRequest;
import com.tridung.caloriesdetect.dto.request.auth.ForgotPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.SetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.request.auth.VerifyEmailRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResendOtpRequest;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.tridung.caloriesdetect.security.AuthCookies;
import org.springframework.security.web.csrf.CsrfToken;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.tridung.caloriesdetect.common.response.BaseResponse;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication and JWT operations")
public class AuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;

    public record CsrfResponse(String token, String headerName) {}

    @GetMapping("/csrf")
    @SecurityRequirements
    @Operation(summary = "Get CSRF token", description = "Fetch with credentials before login, Google login, refresh or logout. Keep the returned masked token in memory and send it in X-XSRF-TOKEN. The matching HttpOnly cookie remains owned by the API host.")
    public BaseResponse<CsrfResponse> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return BaseResponse.success(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }

    private BaseResponse<LoginResponse> tokenResponse(LoginResponse tokens, HttpServletResponse response) {
        authCookies.setRefreshToken(response, tokens.refreshToken());
        return BaseResponse.success(tokens);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Login",
            description = "Authenticate using email and password. Requires X-XSRF-TOKEN from GET /api/auth/csrf. Returns only accessToken, tokenType and expiresIn in JSON; sets a host-only HttpOnly refresh cookie (SameSite=Lax, Path=/api/auth, Secure in production)"
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
            @Valid @RequestBody LoginRequest request, HttpServletResponse response
    ) {
        return tokenResponse(authService.login(request), response);
    }

    @PostMapping("/google")
    @SecurityRequirements
    @Operation(
            summary = "Login with Google",
            description = "Verify a Google ID token. Requires X-XSRF-TOKEN. Returns accessToken, tokenType and expiresIn in JSON and sets the HttpOnly refresh cookie"
    )
    public BaseResponse<LoginResponse> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response
    ) {
        return tokenResponse(authService.loginWithGoogle(request), response);
    }

    @PostMapping("/google/link")
    @Operation(summary = "Link Google", description = "Link a verified Google account with the same email to the authenticated user")
    public BaseResponse<String> linkGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        authService.linkGoogle(request);
        return BaseResponse.success("Google account linked successfully");
    }

    @PostMapping("/set-password")
    @Operation(summary = "Set local password", description = "Add password login to an authenticated, verified Google-only account. Revokes all refresh tokens")
    public BaseResponse<String> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        authService.setPassword(request);
        return BaseResponse.success("Password set successfully. Please log in again");
    }

    @PostMapping("/refresh-token")
    @SecurityRequirements
    @Operation(
            summary = "Refresh token",
            description = "Requires the refresh cookie and X-XSRF-TOKEN; no request body or Bearer token. Atomically revokes the old refresh token and rotates its cookie. Returns accessToken, tokenType and expiresIn only. Missing, expired or revoked cookies return 401"
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
            HttpServletRequest request, HttpServletResponse response
    ) {
        String token = authCookies.readRefreshToken(request);
        if (token == null || token.isBlank()) throw new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        return tokenResponse(authService.refreshToken(new RefreshTokenRequest(token)), response);
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(
            summary = "Register",
            description = "Create an unverified user and send an email OTP valid for 5 minutes. Verify email before login."
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
        BaseResponse<RegisterResponse> response = BaseResponse.success(authService.register(request));
        response.setMessage("Registration successful. Please verify your email using the OTP sent to your inbox");
        return response;
    }

    @PostMapping("/verify-email")
    @SecurityRequirements
    @Operation(summary = "Verify email", description = "Verify a six-digit email OTP, then log in to obtain tokens")
    public BaseResponse<String> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
        return BaseResponse.success("Email verified successfully. You can now log in");
    }

    @PostMapping("/resend-otp")
    @SecurityRequirements
    @Operation(summary = "Resend verification OTP", description = "60-second cooldown; maximum 5 codes per hour including registration")
    public BaseResponse<String> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        try {
            authService.resendOtp(request);
        } catch (AppException exception) {
            // SMTP failure must not reveal that this address belongs to an unverified account.
            // The service transaction has already rolled back, retaining the previous OTP.
            if (exception.getErrorCode() != ErrorCode.EMAIL_DELIVERY_FAILED) {
                throw exception;
            }
        }
        return BaseResponse.success("If email verification is required and the resend limit allows it, a new OTP will be sent");
    }

    @PostMapping("/forgot-password")
    @SecurityRequirements
    @Operation(summary = "Request password reset OTP", description = "Email a six-digit OTP valid for 5 minutes. No login required. 60-second cooldown; maximum 5 codes per hour. Always returns a generic response for valid email input.")
    public BaseResponse<String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            authService.forgotPassword(request);
        } catch (AppException exception) {
            // Catch outside the service transaction so SMTP failure rolls back OTP issuance.
            if (exception.getErrorCode() != ErrorCode.EMAIL_DELIVERY_FAILED) {
                throw exception;
            }
        }
        return BaseResponse.success("If the account is eligible and the request limit allows it, a password reset OTP will be sent to your email");
    }

    @PostMapping("/reset-password")
    @SecurityRequirements
    @Operation(summary = "Confirm password update", description = "Shared confirmation for forgot-password and change-password/request. Verify email and password-reset OTP, update the password and revoke all refresh tokens. No login required. Existing access tokens remain valid until expiration.")
    public BaseResponse<String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return BaseResponse.success("Password reset successfully. Please log in again");
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(
            summary = "Logout",
            description = "Requires X-XSRF-TOKEN; no request body or Bearer token. Idempotently revokes the refresh cookie and any rotated successor, then expires the cookie. Existing access JWTs remain valid until their short expiration"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Logout successful",
            content = @Content(schema = @Schema(
                    implementation = BaseResponse.class
            ))
    )
    public BaseResponse<String> logout(
            HttpServletRequest request, HttpServletResponse response
    ) {
        try {
            String token = authCookies.readRefreshToken(request);
            if (token != null && !token.isBlank()) authService.logout(new LogoutRequest(token));
        } finally {
            authCookies.clearRefreshToken(response);
        }
        return BaseResponse.success("Logout successful");
    }

    @PostMapping("/change-password/request")
    @Operation(summary = "Request password change", description = "Verify the current password and email a PASSWORD_RESET OTP. Requires Bearer JWT. Confirm using /api/auth/reset-password with email, OTP and the new password. Shares cooldown and hourly limits with forgot-password.")
    public BaseResponse<String> requestPasswordChange(@Valid @RequestBody RequestPasswordChangeRequest request) {
        authService.requestPasswordChange(request);
        return BaseResponse.success("Password change OTP sent to your email. The code expires in 5 minutes");
    }

}
